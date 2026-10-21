package com.ledgercore.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, Long> {

    Optional<IdempotencyKeyRecord> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query("""
            update IdempotencyKeyRecord k
            set k.status = com.ledgercore.idempotency.IdempotencyStatus.IN_PROGRESS,
                k.createdAt = CURRENT_TIMESTAMP, k.completedAt = null,
                k.responseStatus = null, k.responseBody = null
            where k.id = :id and k.status = com.ledgercore.idempotency.IdempotencyStatus.FAILED
            """)
    int reclaimFailed(@Param("id") Long id);

    @Modifying
    @Query("""
            update IdempotencyKeyRecord k
            set k.status = com.ledgercore.idempotency.IdempotencyStatus.IN_PROGRESS, k.createdAt = CURRENT_TIMESTAMP
            where k.id = :id and k.status = com.ledgercore.idempotency.IdempotencyStatus.IN_PROGRESS
              and k.createdAt < :staleBefore
            """)
    int reclaimStale(@Param("id") Long id, @Param("staleBefore") Instant staleBefore);

    @Modifying
    @Query("""
            update IdempotencyKeyRecord k
            set k.status = com.ledgercore.idempotency.IdempotencyStatus.COMPLETED,
                k.responseStatus = :responseStatus, k.responseBody = :responseBody,
                k.journalEntryId = :journalEntryId, k.completedAt = CURRENT_TIMESTAMP
            where k.id = :id
            """)
    int markCompleted(@Param("id") Long id, @Param("responseStatus") int responseStatus,
                       @Param("responseBody") String responseBody, @Param("journalEntryId") Long journalEntryId);

    @Modifying
    @Query("""
            update IdempotencyKeyRecord k
            set k.status = com.ledgercore.idempotency.IdempotencyStatus.FAILED,
                k.responseStatus = :responseStatus, k.responseBody = :responseBody,
                k.completedAt = CURRENT_TIMESTAMP
            where k.id = :id
            """)
    int markFailed(@Param("id") Long id, @Param("responseStatus") int responseStatus,
                    @Param("responseBody") String responseBody);
}
