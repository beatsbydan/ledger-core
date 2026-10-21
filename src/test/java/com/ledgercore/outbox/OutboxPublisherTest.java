package com.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.support.AbstractIntegrationTest;
import com.ledgercore.transfer.TransferRequest;
import com.ledgercore.transfer.TransferResponse;
import com.ledgercore.transfer.TransferService;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxPublisherTest extends AbstractIntegrationTest {

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

    private Account newAccount(String number, long opening) {
        return accountRepository.save(new Account(number, "Owner " + number, AccountType.CUSTOMER, "NGN", opening));
    }

    @Test
    void publishesPendingEventAndMarksItPublished() {
        Account source = newAccount("PUB-A-" + System.nanoTime(), 10_000);
        Account destination = newAccount("PUB-B-" + System.nanoTime(), 0);
        TransferResponse response = transferService.transfer(UUID.randomUUID().toString(),
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 1_500, "NGN", "publish test"));
        String expectedKey = response.transferId().toString();

        outboxPublisher.publishPendingBatch();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE journal_entry_id = "
                        + "(SELECT id FROM journal_entries WHERE entry_uuid = ?)",
                String.class, response.transferId());
        assertThat(status).isEqualTo("PUBLISHED");

        ConsumerRecord<String, String> record = findRecordByKey(expectedKey);
        assertThat(record.value()).contains(expectedKey);
        assertThat(record.value()).contains(source.getAccountNumber());
    }

    /**
     * Scans forward from the earliest offset until it finds the record with the expected key,
     * rather than grabbing the first polled record - the topic is shared across this whole
     * test run (singleton Kafka container), so other tests' messages can be on it too.
     */
    private ConsumerRecord<String, String> findRecordByKey(String expectedKey) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            for (int attempt = 0; attempt < 20; attempt++) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (expectedKey.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No Kafka record with key " + expectedKey + " found on topic " + topic);
    }
}
