package com.ledgercore.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountNotFoundException;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransferServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private AccountRepository accountRepository;

    private Account newAccount(String number, long opening) {
        return accountRepository.save(new Account(number, "Owner " + number, AccountType.CUSTOMER, "NGN", opening));
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void happyPathMovesBalancesAndWritesOneEntry() {
        Account source = newAccount("TST-A-" + System.nanoTime(), 10_000);
        Account destination = newAccount("TST-B-" + System.nanoTime(), 0);

        TransferResponse response = transferService.transfer(key(),
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 3_000, "NGN", "payout"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalanceMinorUnits()).isEqualTo(7_000);
        assertThat(accountRepository.findById(destination.getId()).orElseThrow().getBalanceMinorUnits()).isEqualTo(3_000);
    }

    @Test
    void insufficientFundsRollsBackWithNoBalanceChange() {
        Account source = newAccount("TST-C-" + System.nanoTime(), 100);
        Account destination = newAccount("TST-D-" + System.nanoTime(), 0);

        assertThatThrownBy(() -> transferService.transfer(key(),
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 1_000, "NGN", null)))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalanceMinorUnits()).isEqualTo(100);
        assertThat(accountRepository.findById(destination.getId()).orElseThrow().getBalanceMinorUnits()).isEqualTo(0);
    }

    @Test
    void sameAccountTransferIsRejectedBeforeAnyLock() {
        Account account = newAccount("TST-E-" + System.nanoTime(), 10_000);

        assertThatThrownBy(() -> transferService.transfer(key(),
                new TransferRequest(account.getAccountNumber(), account.getAccountNumber(), 100, "NGN", null)))
                .isInstanceOf(InvalidTransferException.class);
    }

    @Test
    void unknownAccountIsNotFound() {
        Account source = newAccount("TST-F-" + System.nanoTime(), 10_000);

        assertThatThrownBy(() -> transferService.transfer(key(),
                new TransferRequest(source.getAccountNumber(), "DOES-NOT-EXIST", 100, "NGN", null)))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
