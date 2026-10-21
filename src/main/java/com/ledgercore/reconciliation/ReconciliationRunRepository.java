package com.ledgercore.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    boolean existsByStatus(ReconciliationStatus status);
}
