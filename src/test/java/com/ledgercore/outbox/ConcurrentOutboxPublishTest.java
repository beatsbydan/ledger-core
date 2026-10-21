package com.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.support.AbstractIntegrationTest;
import com.ledgercore.transfer.TransferRequest;
import com.ledgercore.transfer.TransferService;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Fig. 8 proof: this is what actually shows SKIP LOCKED prevents two relay instances from
 * double-claiming the same outbox row, not just that the SQL compiles. Deliberately uses more
 * rows than one poll's batch size, so draining them takes several rounds and genuinely forces
 * concurrent threads to split the work rather than one thread just grabbing everything upfront.
 */
class ConcurrentOutboxPublishTest extends AbstractIntegrationTest {

    private static final int ROW_COUNT = 120;
    private static final int RELAY_INSTANCES = 6;
    private static final int POLLS_PER_INSTANCE = 15;

    @Autowired
    private TransferService transferService;
    @Autowired
    private OutboxPublisher outboxPublisher;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;
    @Value("${ledgercore.kafka.topic}")
    private String topic;

    @Test
    void noOutboxRowIsEverPublishedTwiceUnderConcurrentPolling() throws Exception {
        Account a = accountRepository.save(new Account("COB-A-" + System.nanoTime(), "A", AccountType.CUSTOMER, "NGN", 10_000_000));
        Account b = accountRepository.save(new Account("COB-B-" + System.nanoTime(), "B", AccountType.CUSTOMER, "NGN", 10_000_000));

        Set<String> expectedKeys = new HashSet<>();
        for (int i = 0; i < ROW_COUNT; i++) {
            var response = transferService.transfer(UUID.randomUUID().toString(),
                    new TransferRequest(a.getAccountNumber(), b.getAccountNumber(), 10, "NGN", "concurrent outbox"));
            expectedKeys.add(response.transferId().toString());
        }

        ExecutorService pool = Executors.newFixedThreadPool(RELAY_INSTANCES);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < RELAY_INSTANCES; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    startSignal.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int poll = 0; poll < POLLS_PER_INSTANCE; poll++) {
                    outboxPublisher.publishPendingBatch();
                }
            }, pool));
        }
        startSignal.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(120, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // Scoped to this test's own two accounts - the shared DB also holds OutboxWriteTest's
        // rows, which are deliberately left PENDING forever; an unscoped count would wrongly
        // pick those up.
        String scopeSql = "SELECT COUNT(*) FROM outbox_events WHERE status = ? AND journal_entry_id IN "
                + "(SELECT DISTINCT journal_entry_id FROM journal_lines WHERE account_id IN (?, ?))";
        Long publishedCount = jdbcTemplate.queryForObject(scopeSql, Long.class, "PUBLISHED", a.getId(), b.getId());
        Long pendingCount = jdbcTemplate.queryForObject(scopeSql, Long.class, "PENDING", a.getId(), b.getId());
        assertThat(pendingCount).isZero();
        assertThat(publishedCount).isEqualTo((long) ROW_COUNT);

        var keyCounts = countMessagesByKey(expectedKeys);
        for (String key : expectedKeys) {
            assertThat(keyCounts.getOrDefault(key, 0))
                    .as("entry %s should be published to Kafka exactly once", key)
                    .isEqualTo(1);
        }
    }

    private java.util.Map<String, Integer> countMessagesByKey(Set<String> expectedKeys) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            int emptyPolls = 0;
            while (emptyPolls < 6) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : records) {
                    if (expectedKeys.contains(record.key())) {
                        counts.merge(record.key(), 1, Integer::sum);
                    }
                }
                if (counts.size() >= expectedKeys.size()
                        && counts.values().stream().allMatch(c -> c >= 1)) {
                    // seen at least one message for every expected key - safe to stop early
                    break;
                }
            }
        }
        return counts;
    }
}
