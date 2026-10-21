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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the whole pipeline end-to-end against real Postgres: real transfers (real UUIDs, not
 * hand-picked fixtures - see the architecture doc's note on why that matters for the sort-order
 * assumption), a crafted statement file, and the job wired exactly as {@code run()} invokes it.
 *
 * The reconciliation job scans the *entire* ledger table by design (Fig. 10/11 - it's not
 * scoped to any one account), but this test runs in a Postgres container shared with every other
 * test class, which never truncates between classes. So the statement file isn't just "this
 * test's rows" - it starts from a faithful snapshot of whatever already exists in the ledger
 * (which the job will otherwise see with no bank-side counterpart) and layers this test's own
 * deliberately-seeded mismatches on top of that baseline.
 */
class ReconciliationJobTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private ReconciliationJob reconciliationJob;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Account newAccount(String number, long opening) {
        return accountRepository.save(new Account(number, "Owner " + number, AccountType.CUSTOMER, "NGN", opening));
    }

    @Test
    void reconciliationFindsExactlyTheSeededMismatches() throws IOException {
        List<String> statementLines = snapshotExistingLedgerLinesAsStatementLines();

        Account source = newAccount("RCN-A-" + System.nanoTime(), 1_000_000);
        Account destination = newAccount("RCN-B-" + System.nanoTime(), 0);

        TransferResponse matched = transferService.transfer(UUID.randomUUID().toString(),
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 1_000, "NGN", "matched"));
        TransferResponse amountWrong = transferService.transfer(UUID.randomUUID().toString(),
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 2_000, "NGN", "amount wrong on bank side"));
        TransferResponse missingFromBank = transferService.transfer(UUID.randomUUID().toString(),
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 3_000, "NGN", "never settled externally"));

        // matched: both lines copied through unchanged
        statementLines.add(statementLine(matched.transferId().toString(), source.getAccountNumber(), 1_000, "DEBIT"));
        statementLines.add(statementLine(matched.transferId().toString(), destination.getAccountNumber(), 1_000, "CREDIT"));

        // amountWrong: bank's copy of the debit leg reports a different amount -> AMOUNT_MISMATCH
        statementLines.add(statementLine(amountWrong.transferId().toString(), source.getAccountNumber(), 9_999, "DEBIT"));
        statementLines.add(statementLine(amountWrong.transferId().toString(), destination.getAccountNumber(), 2_000, "CREDIT"));

        // missingFromBank: omitted entirely from the statement -> both its lines are LEDGER_ONLY

        // a bank-side row with no ledger counterpart at all -> BANK_ONLY
        String bankOnlyReference = UUID.randomUUID().toString();
        statementLines.add(statementLine(bankOnlyReference, destination.getAccountNumber(), 777, "CREDIT"));

        statementLines.sort(Comparator.naturalOrder());
        Path statementFile = Files.createTempFile("bank-statement-test", ".csv");
        Files.write(statementFile, statementLines);

        reconciliationJob.run(statementFile);

        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT * FROM reconciliation_runs ORDER BY id DESC LIMIT 1");
        assertThat(run.get("status")).isEqualTo("COMPLETED");

        Long runId = ((Number) run.get("id")).longValue();
        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList(
                "SELECT mismatch_type, entry_uuid, bank_reference, account_number, "
                        + "ledger_amount_minor_units, bank_amount_minor_units "
                        + "FROM reconciliation_mismatches WHERE run_id = ? "
                        + "AND (entry_uuid IN (?, ?) OR bank_reference = ?)",
                runId, amountWrong.transferId().toString(), missingFromBank.transferId().toString(), bankOnlyReference);

        assertThat(mismatches).hasSize(4);
        assertThat(mismatches).anySatisfy(m -> {
            assertThat(m.get("mismatch_type")).isEqualTo("AMOUNT_MISMATCH");
            assertThat(m.get("entry_uuid")).isEqualTo(amountWrong.transferId().toString());
            assertThat(m.get("account_number")).isEqualTo(source.getAccountNumber());
            assertThat(((Number) m.get("ledger_amount_minor_units")).longValue()).isEqualTo(2_000);
            assertThat(((Number) m.get("bank_amount_minor_units")).longValue()).isEqualTo(9_999);
        });
        assertThat(mismatches).filteredOn(m -> "LEDGER_ONLY".equals(m.get("mismatch_type"))).hasSize(2)
                .allSatisfy(m -> assertThat(m.get("entry_uuid")).isEqualTo(missingFromBank.transferId().toString()));
        assertThat(mismatches).anySatisfy(m -> {
            assertThat(m.get("mismatch_type")).isEqualTo("BANK_ONLY");
            assertThat(m.get("bank_reference")).isEqualTo(bankOnlyReference);
        });
    }

    private List<String> snapshotExistingLedgerLinesAsStatementLines() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT CAST(e.entry_uuid AS text) AS reference, a.account_number, l.direction, l.amount_minor_units "
                        + "FROM journal_lines l "
                        + "JOIN journal_entries e ON e.id = l.journal_entry_id "
                        + "JOIN accounts a ON a.id = l.account_id");
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            lines.add(statementLine((String) row.get("reference"), (String) row.get("account_number"),
                    ((Number) row.get("amount_minor_units")).longValue(), (String) row.get("direction")));
        }
        return lines;
    }

    private String statementLine(String reference, String accountNumber, long amount, String direction) {
        return reference + "," + accountNumber + "," + amount + "," + direction;
    }
}
