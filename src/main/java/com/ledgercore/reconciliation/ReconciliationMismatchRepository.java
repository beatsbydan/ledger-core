package com.ledgercore.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationMismatchRepository extends JpaRepository<ReconciliationMismatch, Long> {
}
