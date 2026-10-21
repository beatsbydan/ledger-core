package com.ledgercore.idempotency;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every operation here is its own short REQUIRES_NEW transaction. This is deliberate: the
 * retry-wait loop in {@link IdempotencyService} polls this store repeatedly while a racing
 * request resolves, and that polling must happen between short transactions, not inside one
 * long-held one - holding a single transaction open across a sleep loop would tie up a DB
 * connection for the whole wait window under duplicate-request load.
 */
@Component
class IdempotencyKeyStore {

    private final IdempotencyKeyRepository repository;

    IdempotencyKeyStore(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Throws (does not catch) on a unique-key collision - deliberately. Per the JPA spec,
     * a flush failure marks the transaction rollback-only regardless of whether the exception
     * is caught; catching it inside this @Transactional method would still leave Spring trying
     * to commit an already-poisoned transaction on return, surfacing as UnexpectedRollbackException
     * instead of the real cause. The caller (IdempotencyService.claim) catches this after the
     * REQUIRES_NEW transaction has already rolled back cleanly.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Long tryInsert(String idempotencyKey, String fingerprint) {
        IdempotencyKeyRecord record = new IdempotencyKeyRecord(idempotencyKey, fingerprint);
        repository.saveAndFlush(record);
        return record.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    IdempotencyKeyRecord readByKey(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency key disappeared: " + idempotencyKey));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean reclaimFailed(Long id) {
        return repository.reclaimFailed(id) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean reclaimStale(Long id, Instant staleBefore) {
        return repository.reclaimStale(id, staleBefore) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markCompleted(Long id, int responseStatus, String responseBody, Long journalEntryId) {
        repository.markCompleted(id, responseStatus, responseBody, journalEntryId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markFailed(Long id, int responseStatus, String responseBody) {
        repository.markFailed(id, responseStatus, responseBody);
    }
}
