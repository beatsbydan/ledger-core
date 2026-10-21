package com.ledgercore.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The relay from Fig. 8 of the architecture doc. One poll cycle is one transaction: the batch
 * fetch's SKIP LOCKED holds the row locks for the duration of this method, which is exactly
 * what lets a second instance's concurrent poll skip these rows instead of blocking on or
 * double-claiming them. Each row's publish is independently try/caught so one failure doesn't
 * roll back the status updates already applied to earlier rows in the same batch.
 */
@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxPublisher(
            OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${ledgercore.outbox.batch-size:50}") int batchSize,
            @Value("${ledgercore.outbox.send-timeout-ms:5000}") long sendTimeoutMs
    ) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${ledgercore.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPendingBatch() {
        List<OutboxEvent> batch = repository.findPendingBatchForUpdate(batchSize);
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            String key = objectMapper.readValue(event.getPayload(), OutboxPayload.class).entryUuid().toString();
            kafkaTemplate.send(event.getKafkaTopic(), key, event.getPayload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            event.markPublished();
        } catch (Exception e) {
            // Caught per-row, deliberately: a broker timeout or a crash-adjacent failure here
            // must not undo the PUBLISHED status already committed in memory for earlier rows
            // in this same batch/transaction. The row stays PENDING and is retried next poll.
            event.incrementAttempts();
            log.warn("Outbox publish failed for event {} (attempt {}): {}", event.getId(), event.getAttempts(), e.toString());
        }
    }
}
