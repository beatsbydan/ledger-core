package com.ledgercore.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.support.AbstractIntegrationTest;
import com.ledgercore.transfer.TransferRequest;
import com.ledgercore.transfer.TransferService;
import java.util.ArrayList;
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

/**
 * The headline proof: fires many concurrent transfers alternating direction between the same
 * two accounts - the exact scenario the architecture doc's deadlock-avoidance diagram covers -
 * and checks the invariants hold under real contention, not just on the happy path.
 */
class ConcurrentTransferTest extends AbstractIntegrationTest {

    private static final int TRANSFER_COUNT = 80;
    private static final long AMOUNT = 500L;
    private static final long OPENING_BALANCE = 1_000_000L;

    @Autowired
    private TransferService transferService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentOppositeDirectionTransfersNeverOverdrawOrDriftBalances() throws Exception {
        Account a = accountRepository.save(new Account("CCT-A-" + System.nanoTime(), "A", AccountType.CUSTOMER, "NGN", OPENING_BALANCE));
        Account b = accountRepository.save(new Account("CCT-B-" + System.nanoTime(), "B", AccountType.CUSTOMER, "NGN", OPENING_BALANCE));
        long totalBefore = a.getBalanceMinorUnits() + b.getBalanceMinorUnits();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < TRANSFER_COUNT; i++) {
            boolean aToB = i % 2 == 0;
            String source = aToB ? a.getAccountNumber() : b.getAccountNumber();
            String destination = aToB ? b.getAccountNumber() : a.getAccountNumber();
            String idempotencyKey = UUID.randomUUID().toString();

            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    startSignal.await();
                    transferService.transfer(idempotencyKey,
                            new TransferRequest(source, destination, AMOUNT, "NGN", "concurrency test"));
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (RuntimeException e) {
                    return false;
                }
            }, pool));
        }

        startSignal.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        long successCount = futures.stream().filter(CompletableFuture::join).count();
        assertThat(successCount).isEqualTo(TRANSFER_COUNT); // balances are large relative to AMOUNT - none should fail

        long balanceA = accountRepository.findById(a.getId()).orElseThrow().getBalanceMinorUnits();
        long balanceB = accountRepository.findById(b.getId()).orElseThrow().getBalanceMinorUnits();

        assertThat(balanceA).isGreaterThanOrEqualTo(0);
        assertThat(balanceB).isGreaterThanOrEqualTo(0);
        assertThat(balanceA + balanceB).isEqualTo(totalBefore);

        Long debitSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_minor_units), 0) FROM journal_lines "
                        + "WHERE direction = 'DEBIT' AND account_id IN (?, ?)", Long.class, a.getId(), b.getId());
        Long creditSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_minor_units), 0) FROM journal_lines "
                        + "WHERE direction = 'CREDIT' AND account_id IN (?, ?)", Long.class, a.getId(), b.getId());
        assertThat(debitSum).isEqualTo(creditSum);

        Long entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT journal_entry_id) FROM journal_lines WHERE account_id IN (?, ?)",
                Long.class, a.getId(), b.getId());
        assertThat(entryCount).isEqualTo((long) TRANSFER_COUNT);
    }
}
