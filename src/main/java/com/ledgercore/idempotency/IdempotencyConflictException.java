package com.ledgercore.idempotency;

/** Same Idempotency-Key, different request body. Always rejected - never silently reused. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request body");
    }
}
