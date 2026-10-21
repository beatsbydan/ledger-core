package com.ledgercore.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * SKIP LOCKED has no JPQL/@Lock equivalent, so this is a native query - the one place in
     * the codebase that drops to raw SQL rather than forcing an awkward ORM workaround. Locking
     * the whole batch for the caller's transaction duration is what lets a second relay
     * instance's concurrent poll skip these rows instead of blocking on or double-processing them.
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' "
            + "ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findPendingBatchForUpdate(@Param("limit") int limit);
}
