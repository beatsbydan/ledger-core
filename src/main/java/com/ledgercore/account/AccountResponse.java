package com.ledgercore.account;

import java.time.Instant;

public record AccountResponse(
        String accountNumber,
        String ownerName,
        AccountType accountType,
        AccountStatus status,
        String currency,
        long balanceMinorUnits,
        Instant createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getAccountNumber(),
                account.getOwnerName(),
                account.getAccountType(),
                account.getStatus(),
                account.getCurrency(),
                account.getBalanceMinorUnits(),
                account.getCreatedAt()
        );
    }
}
