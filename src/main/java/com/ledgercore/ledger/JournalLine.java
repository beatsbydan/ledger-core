package com.ledgercore.ledger;

import com.ledgercore.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "journal_lines")
public class JournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry journalEntry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private EntryDirection direction;

    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column(name = "line_number", nullable = false)
    private short lineNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JournalLine() {
        // JPA
    }

    public JournalLine(Account account, EntryDirection direction, long amountMinorUnits) {
        if (amountMinorUnits <= 0) {
            throw new IllegalArgumentException("amountMinorUnits must be positive, got " + amountMinorUnits);
        }
        this.account = account;
        this.direction = direction;
        this.amountMinorUnits = amountMinorUnits;
        this.createdAt = Instant.now();
    }

    void assignTo(JournalEntry journalEntry, int lineNumber) {
        this.journalEntry = journalEntry;
        this.lineNumber = (short) lineNumber;
    }

    public Long getId() {
        return id;
    }

    public JournalEntry getJournalEntry() {
        return journalEntry;
    }

    public Account getAccount() {
        return account;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmountMinorUnits() {
        return amountMinorUnits;
    }

    public short getLineNumber() {
        return lineNumber;
    }
}
