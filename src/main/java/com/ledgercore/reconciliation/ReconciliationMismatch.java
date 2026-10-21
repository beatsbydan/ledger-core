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

/**
 * {@code runId}, {@code entryUuid} and {@code bankReference} are plain columns, not FKs - a
 * BANK_ONLY mismatch has no journal entry to reference at all, so entryUuid is nullable; a
 * LEDGER_ONLY mismatch likewise has no bank reference.
 */
@Entity
@Table(name = "reconciliation_mismatches")
public class ReconciliationMismatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mismatch_type", nullable = false)
    private MismatchType mismatchType;

    @Column(name = "entry_uuid")
    private String entryUuid;

    @Column(name = "bank_reference")
    private String bankReference;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "ledger_amount_minor_units")
    private Long ledgerAmountMinorUnits;

    @Column(name = "bank_amount_minor_units")
    private Long bankAmountMinorUnits;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationMismatch() {
        // JPA
    }

    public ReconciliationMismatch(Long runId, Mismatch mismatch) {
        this.runId = runId;
        this.mismatchType = mismatch.type();
        this.entryUuid = mismatch.entryUuid();
        this.bankReference = mismatch.bankReference();
        this.accountNumber = mismatch.accountNumber();
        this.ledgerAmountMinorUnits = mismatch.ledgerAmountMinorUnits();
        this.bankAmountMinorUnits = mismatch.bankAmountMinorUnits();
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getRunId() {
        return runId;
    }

    public MismatchType getMismatchType() {
        return mismatchType;
    }

    public String getEntryUuid() {
        return entryUuid;
    }

    public String getBankReference() {
        return bankReference;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public Long getLedgerAmountMinorUnits() {
        return ledgerAmountMinorUnits;
    }

    public Long getBankAmountMinorUnits() {
        return bankAmountMinorUnits;
    }
}
