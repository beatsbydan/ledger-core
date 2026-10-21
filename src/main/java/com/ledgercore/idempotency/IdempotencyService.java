package com.ledgercore.idempotency;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Claim/complete/fail are three independent transactions, never one - a claim has to be
 * visible to a racing duplicate the instant it commits, long before the transfer it guards
 * has finished. See the LedgerCore architecture doc's idempotency flow.
 *
 * The retry-wait loop below polls {@link IdempotencyKeyStore} - each poll is its own short
 * transaction - and sleeps between polls outside of any transaction, so a flood of duplicate
 * requests waiting on the same key doesn't each tie up a DB connection for the whole wait window.
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyStore store;
    private final long inProgressWaitMs;
    private final long pollIntervalMs;
    private final long staleAfterSeconds;

    public IdempotencyService(
            IdempotencyKeyStore store,
            @Value("${ledgercore.idempotency.in-progress-wait-ms:1500}") long inProgressWaitMs,
            @Value("${ledgercore.idempotency.in-progress-poll-interval-ms:150}") long pollIntervalMs,
            @Value("${ledgercore.idempotency.in-progress-stale-after-seconds:30}") long staleAfterSeconds
    ) {
        this.store = store;
        this.inProgressWaitMs = inProgressWaitMs;
        this.pollIntervalMs = pollIntervalMs;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    public ClaimResult claim(String idempotencyKey, String fingerprint) {
        try {
            return new ClaimResult.NewClaim(store.tryInsert(idempotencyKey, fingerprint));
        } catch (DataIntegrityViolationException e) {
            return resolveAgainstExisting(idempotencyKey, fingerprint, System.currentTimeMillis());
        }
    }

    private ClaimResult resolveAgainstExisting(String idempotencyKey, String fingerprint, long startedAtMs) {
        while (true) {
            IdempotencyKeyRecord existing = store.readByKey(idempotencyKey);

            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }

            switch (existing.getStatus()) {
                case COMPLETED -> {
                    return new ClaimResult.Replay(existing.getResponseStatus(), existing.getResponseBody());
                }
                case FAILED -> {
                    if (store.reclaimFailed(existing.getId())) {
                        return new ClaimResult.NewClaim(existing.getId());
                    }
                    // someone else reclaimed it between our read and write - loop and re-check its new state
                }
                case IN_PROGRESS -> {
                    Instant staleBefore = Instant.now().minusSeconds(staleAfterSeconds);
                    if (existing.getCreatedAt().isBefore(staleBefore)) {
                        if (store.reclaimStale(existing.getId(), staleBefore)) {
                            return new ClaimResult.NewClaim(existing.getId());
                        }
                    } else if (System.currentTimeMillis() - startedAtMs >= inProgressWaitMs) {
                        throw new IdempotencyInProgressException(idempotencyKey);
                    }
                }
            }

            sleep(pollIntervalMs);
        }
    }

    public void complete(Long recordId, int responseStatus, String responseBodyJson, Long journalEntryId) {
        store.markCompleted(recordId, responseStatus, responseBodyJson, journalEntryId);
    }

    public void fail(Long recordId, int responseStatus, String responseBodyJson) {
        store.markFailed(recordId, responseStatus, responseBodyJson);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting on idempotency key resolution", e);
        }
    }
}
