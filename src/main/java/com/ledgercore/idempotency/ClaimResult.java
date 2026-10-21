package com.ledgercore.idempotency;

/** Outcome of {@link IdempotencyService#claim}. Conflict and in-progress-timeout are thrown, not returned. */
public sealed interface ClaimResult {

    record NewClaim(Long recordId) implements ClaimResult {
    }

    record Replay(int responseStatus, String responseBody) implements ClaimResult {
    }
}
