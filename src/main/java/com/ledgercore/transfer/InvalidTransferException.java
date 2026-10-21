package com.ledgercore.transfer;

/** Rejected before any account lock is attempted - e.g. source == destination, missing Idempotency-Key. */
public class InvalidTransferException extends RuntimeException {

    public InvalidTransferException(String message) {
        super(message);
    }
}
