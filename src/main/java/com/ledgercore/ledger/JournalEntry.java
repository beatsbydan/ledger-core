package com.ledgercore.ledger;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immutable once committed - enforced by a DB trigger, not just application discipline.
 * A correction is a new entry referencing this one via {@link #reversedEntry}, never an edit.
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_uuid", nullable = false, unique = true)
    private UUID entryUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private JournalEntryType entryType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversed_entry_id")
    private JournalEntry reversedEntry;

    @Column(name = "description")
    private String description;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<JournalLine> lines = new ArrayList<>();

    protected JournalEntry() {
        // JPA
    }

    public JournalEntry(JournalEntryType entryType, String currency, String description,
                         JournalEntry reversedEntry, String createdBy) {
        this.entryUuid = UUID.randomUUID();
        this.entryType = entryType;
        this.currency = currency;
        this.description = description;
        this.reversedEntry = reversedEntry;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void addLine(JournalLine line) {
        line.assignTo(this, lines.size() + 1);
        lines.add(line);
    }

    public Long getId() {
        return id;
    }

    public UUID getEntryUuid() {
        return entryUuid;
    }

    public JournalEntryType getEntryType() {
        return entryType;
    }

    public JournalEntry getReversedEntry() {
        return reversedEntry;
    }

    public String getDescription() {
        return description;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<JournalLine> getLines() {
        return lines;
    }
}
