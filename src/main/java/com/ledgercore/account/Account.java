package com.ledgercore.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "balance_minor_units", nullable = false)
    private long balanceMinorUnits;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // JPA
    }

    public Account(String accountNumber, String ownerName, AccountType accountType,
                   String currency, long openingBalanceMinorUnits) {
        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.accountType = accountType;
        this.status = AccountStatus.ACTIVE;
        this.currency = currency;
        this.balanceMinorUnits = openingBalanceMinorUnits;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Mutates the locked row; caller is responsible for holding the row lock and validating funds. */
    public void applyDebit(long amountMinorUnits) {
        this.balanceMinorUnits -= amountMinorUnits;
        this.updatedAt = Instant.now();
    }

    /** Mutates the locked row; caller is responsible for holding the row lock. */
    public void applyCredit(long amountMinorUnits) {
        this.balanceMinorUnits += amountMinorUnits;
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceMinorUnits() {
        return balanceMinorUnits;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
