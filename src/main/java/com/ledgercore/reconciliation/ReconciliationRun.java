package com.ledgercore.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReconciliationStatus status;

    @Column(name = "ledger_rows_scanned", nullable = false)
    private int ledgerRowsScanned;

    @Column(name = "bank_rows_scanned", nullable = false)
    private int bankRowsScanned;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ReconciliationRun() {
        // JPA
    }

    private ReconciliationRun(LocalDate runDate, ReconciliationStatus status) {
        this.runDate = runDate;
        this.status = status;
        this.startedAt = Instant.now();
    }

    public static ReconciliationRun starting(LocalDate runDate) {
        return new ReconciliationRun(runDate, ReconciliationStatus.RUNNING);
    }

    /** For the case where the run never gets as far as RUNNING - e.g. the statement file is missing. */
    public static ReconciliationRun failedBeforeStarting(LocalDate runDate, String reason) {
        ReconciliationRun run = new ReconciliationRun(runDate, ReconciliationStatus.FAILED);
        run.failureReason = reason;
        run.completedAt = Instant.now();
        return run;
    }

    public void complete(int ledgerRowsScanned, int bankRowsScanned, int mismatchCount) {
        this.status = ReconciliationStatus.COMPLETED;
        this.ledgerRowsScanned = ledgerRowsScanned;
        this.bankRowsScanned = bankRowsScanned;
        this.mismatchCount = mismatchCount;
        this.completedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public LocalDate getRunDate() {
        return runDate;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public int getLedgerRowsScanned() {
        return ledgerRowsScanned;
    }

    public int getBankRowsScanned() {
        return bankRowsScanned;
    }

    public int getMismatchCount() {
        return mismatchCount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
