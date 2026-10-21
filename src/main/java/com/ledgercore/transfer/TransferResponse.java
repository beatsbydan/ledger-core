package com.ledgercore.transfer;

import com.ledgercore.ledger.EntryDirection;
import com.ledgercore.ledger.JournalEntry;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        String sourceAccountNumber,
        String destinationAccountNumber,
        long amountMinorUnits,
        String currency,
        String status,
        Instant createdAt
) {
    public static TransferResponse completed(JournalEntry entry, TransferRequest request) {
        return new TransferResponse(
                entry.getEntryUuid(),
                request.sourceAccountNumber(),
                request.destinationAccountNumber(),
                request.amountMinorUnits(),
                request.currency(),
                "COMPLETED",
                entry.getCreatedAt()
        );
    }

    /** Derives source/destination/amount from the entry's own lines - used when reading a transfer back by id. */
    public static TransferResponse from(JournalEntry entry) {
        String sourceAccountNumber = null;
        String destinationAccountNumber = null;
        long amount = 0L;
        for (var line : entry.getLines()) {
            if (line.getDirection() == EntryDirection.DEBIT) {
                sourceAccountNumber = line.getAccount().getAccountNumber();
            } else {
                destinationAccountNumber = line.getAccount().getAccountNumber();
            }
            amount = line.getAmountMinorUnits();
        }
        return new TransferResponse(
                entry.getEntryUuid(),
                sourceAccountNumber,
                destinationAccountNumber,
                amount,
                entry.getCurrency(),
                "COMPLETED",
                entry.getCreatedAt()
        );
    }
}
