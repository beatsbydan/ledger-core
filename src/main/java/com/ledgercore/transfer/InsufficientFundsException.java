package com.ledgercore.transfer;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String accountNumber, long requested, long available) {
        super("Account " + accountNumber + " has insufficient funds: requested " + requested
                + ", available " + available);
    }
}
