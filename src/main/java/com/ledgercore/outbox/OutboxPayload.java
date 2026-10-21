package com.ledgercore.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The minimal event the relay needs to publish - never re-derived from journal_lines at publish time. */
public record OutboxPayload(
        UUID entryUuid,
        String entryType,
        String currency,
        List<LinePayload> lines,
        Instant occurredAt
) {
    public record LinePayload(String accountNumber, String direction, long amountMinorUnits) {
    }
}
