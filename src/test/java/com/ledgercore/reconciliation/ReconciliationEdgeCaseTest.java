package com.ledgercore.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.support.AbstractIntegrationTest;
import com.ledgercore.transfer.TransferRequest;
import com.ledgercore.transfer.TransferResponse;
import com.ledgercore.transfer.TransferService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ReconciliationEdgeCaseTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private ReconciliationJob reconciliationJob;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void oneMalformedLineIsSkippedAndTheRunStillCompletes() throws IOException {
        Account source = accountRepository.save(new Account("RCE-A-" + System.nanoTime(), "A", AccountType.CUSTOMER, "NGN", 10_000));
        Account destination = accountRepository.save(new Account("RCE-B-" + System.nanoTime(), "B", AccountType.CUSTOMER, "NGN", 0));
        TransferResponse transfer = transferService.transfer(UUID.randomUUID().toString(),
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 500, "NGN", "edge case"));

        List<String> lines = new java.util.ArrayList<>(List.of(
                "this line has way too many fields,to,possibly,be,valid,garbage",
                transfer.transferId() + "," + source.getAccountNumber() + ",500,DEBIT",
                transfer.transferId() + "," + destination.getAccountNumber() + ",500,CREDIT"
        ));
        lines.sort(java.util.Comparator.naturalOrder());
        Path file = Files.createTempFile("bank-statement-malformed", ".csv");
        Files.write(file, lines);

        reconciliationJob.run(file);

        Map<String, Object> run = jdbcTemplate.queryForMap("SELECT * FROM reconciliation_runs ORDER BY id DESC LIMIT 1");
        assertThat(run.get("status")).isEqualTo("COMPLETED"); // the garbage line didn't abort the run

        Long runId = ((Number) run.get("id")).longValue();
        Long mismatchesForThisTransfer = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_mismatches WHERE run_id = ? AND entry_uuid = ?",
                Long.class, runId, transfer.transferId().toString());
        assertThat(mismatchesForThisTransfer).isZero(); // both valid lines still matched correctly
    }

    @Test
    void missingStatementFileRecordsAFailedRunInsteadOfThrowing() {
        Path missing = Path.of("definitely-does-not-exist-" + UUID.randomUUID() + ".csv");

        reconciliationJob.run(missing); // must not throw out of this call

        Map<String, Object> run = jdbcTemplate.queryForMap("SELECT * FROM reconciliation_runs ORDER BY id DESC LIMIT 1");
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("failure_reason")).isNotNull();
    }
}
