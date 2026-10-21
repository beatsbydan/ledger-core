package com.ledgercore.account;

public class AccountNotActiveException extends RuntimeException {

    public AccountNotActiveException(String accountNumber, AccountStatus status) {
        super("Account " + accountNumber + " is not active (status=" + status + ")");
    }
}
