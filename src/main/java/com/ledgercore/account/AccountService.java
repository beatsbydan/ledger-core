package com.ledgercore.account;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Account createAccount(CreateAccountRequest request) {
        if (accountRepository.existsByAccountNumber(request.accountNumber())) {
            throw new DuplicateAccountNumberException(request.accountNumber());
        }
        Account account = new Account(
                request.accountNumber(),
                request.ownerName(),
                AccountType.CUSTOMER,
                request.currency(),
                request.openingBalanceOrZero()
        );
        try {
            return accountRepository.save(account);
        } catch (DataIntegrityViolationException e) {
            // Backstop for the race between the existsBy check above and the insert -
            // the unique constraint on account_number is the authoritative guarantee.
            throw new DuplicateAccountNumberException(request.accountNumber());
        }
    }

    @Transactional(readOnly = true)
    public Account getByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }
}
