package com.ledgercore.idempotency;

/** Same Idempotency-Key, same body, but the original request is still being processed. */
public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException(String idempotencyKey) {
        super("Request with Idempotency-Key '" + idempotencyKey + "' is still being processed");
    }
}
