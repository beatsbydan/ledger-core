package com.ledgercore.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.support.AbstractIntegrationTest;
import com.ledgercore.transfer.InsufficientFundsException;
import com.ledgercore.transfer.TransferRequest;
import com.ledgercore.transfer.TransferResponse;
import com.ledgercore.transfer.TransferService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Account newAccount(String number, long opening) {
        return accountRepository.save(new Account(number, "Owner " + number, AccountType.CUSTOMER, "NGN", opening));
    }

    @Test
    void concurrentDuplicateRequestsResolveToExactlyOneTransfer() throws Exception {
        Account source = newAccount("IDK-A-" + System.nanoTime(), 1_000_000);
        Account destination = newAccount("IDK-B-" + System.nanoTime(), 0);
        String sharedKey = UUID.randomUUID().toString();
        TransferRequest request = new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 2_000, "NGN", "dup");

        int callers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<CompletableFuture<TransferResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    startSignal.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return transferService.transfer(sharedKey, request);
            }, pool));
        }
        startSignal.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        List<UUID> transferIds = futures.stream().map(f -> f.join().transferId()).distinct().toList();
        assertThat(transferIds).hasSize(1);

        Long entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT journal_entry_id) FROM journal_lines WHERE account_id IN (?, ?)",
                Long.class, source.getId(), destination.getId());
        assertThat(entryCount).isEqualTo(1L);

        assertThat(accountRepository.findById(destination.getId()).orElseThrow().getBalanceMinorUnits())
                .isEqualTo(2_000); // not 20x - the duplicate calls never re-executed the transfer
    }

    @Test
    void sameKeyDifferentBodyIsRejectedWithoutExecutingASecondTransfer() {
        Account source = newAccount("IDK-C-" + System.nanoTime(), 1_000_000);
        Account destination = newAccount("IDK-D-" + System.nanoTime(), 0);
        String key = UUID.randomUUID().toString();

        transferService.transfer(key,
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 1_000, "NGN", "first"));

        assertThatThrownBy(() -> transferService.transfer(key,
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 9_999, "NGN", "different body")))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(accountRepository.findById(destination.getId()).orElseThrow().getBalanceMinorUnits())
                .isEqualTo(1_000); // the conflicting request never moved any money
    }

    @Test
    void retryAfterFailureSucceedsExactlyOnce() {
        Account source = newAccount("IDK-E-" + System.nanoTime(), 0);
        Account destination = newAccount("IDK-F-" + System.nanoTime(), 0);
        String key = UUID.randomUUID().toString();
        TransferRequest request = new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 500, "NGN", "retry");

        assertThatThrownBy(() -> transferService.transfer(key, request))
                .isInstanceOf(InsufficientFundsException.class);

        // fund the account, then retry with the exact same key and body
        Account fundedSource = accountRepository.findById(source.getId()).orElseThrow();
        fundedSource.applyCredit(500);
        accountRepository.save(fundedSource);

        TransferResponse response = transferService.transfer(key, request);
        assertThat(response.status()).isEqualTo("COMPLETED");

        Long entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT journal_entry_id) FROM journal_lines WHERE account_id IN (?, ?)",
                Long.class, source.getId(), destination.getId());
        assertThat(entryCount).isEqualTo(1L); // the failed attempt wrote no journal entry
    }
}
