package com.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.ledger.LedgerService;
import com.ledgercore.support.AbstractIntegrationTest;
import com.ledgercore.transfer.InsufficientFundsException;
import com.ledgercore.transfer.TransferRequest;
import com.ledgercore.transfer.TransferResponse;
import com.ledgercore.transfer.TransferService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxWriteTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private Account newAccount(String number, long opening) {
        return accountRepository.save(new Account(number, "Owner " + number, AccountType.CUSTOMER, "NGN", opening));
    }

    @Test
    void successfulTransferWritesExactlyOnePendingOutboxRowWithCorrectPayload() throws Exception {
        Account source = newAccount("OBX-A-" + System.nanoTime(), 10_000);
        Account destination = newAccount("OBX-B-" + System.nanoTime(), 0);

        TransferResponse response = transferService.transfer(UUID.randomUUID().toString(),
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 2_500, "NGN", "outbox check"));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, event_type, payload FROM outbox_events WHERE journal_entry_id = "
                        + "(SELECT id FROM journal_entries WHERE entry_uuid = ?)", response.transferId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("PENDING");
        assertThat(rows.get(0).get("event_type")).isEqualTo("TRANSFER_COMPLETED");

        // jsonb columns come back from plain JDBC as a PGobject, not a String.
        OutboxPayload payload = objectMapper.readValue(rows.get(0).get("payload").toString(), OutboxPayload.class);
        assertThat(payload.entryUuid()).isEqualTo(response.transferId());
        assertThat(payload.lines()).hasSize(2);
        assertThat(payload.lines()).anySatisfy(line -> {
            assertThat(line.accountNumber()).isEqualTo(source.getAccountNumber());
            assertThat(line.direction()).isEqualTo("DEBIT");
            assertThat(line.amountMinorUnits()).isEqualTo(2_500);
        });
        assertThat(payload.lines()).anySatisfy(line -> {
            assertThat(line.accountNumber()).isEqualTo(destination.getAccountNumber());
            assertThat(line.direction()).isEqualTo("CREDIT");
        });
    }

    @Test
    void insufficientFundsWritesNoOutboxRow() {
        Account source = newAccount("OBX-C-" + System.nanoTime(), 100);
        Account destination = newAccount("OBX-D-" + System.nanoTime(), 0);

        assertThatThrownBy(() -> transferService.transfer(UUID.randomUUID().toString(),
                new TransferRequest(source.getAccountNumber(), destination.getAccountNumber(), 1_000, "NGN", null)))
                .isInstanceOf(InsufficientFundsException.class);

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE journal_entry_id IN "
                        + "(SELECT id FROM journal_lines WHERE account_id IN (?, ?))",
                Long.class, source.getId(), destination.getId());
        assertThat(count).isZero();
    }

    @Test
    void reversalWritesItsOwnOutboxRowReferencingTheReversalEntry() {
        Account a = newAccount("OBX-E-" + System.nanoTime(), 10_000);
        Account b = newAccount("OBX-F-" + System.nanoTime(), 0);
        TransferResponse original = transferService.transfer(UUID.randomUUID().toString(),
                new TransferRequest(a.getAccountNumber(), b.getAccountNumber(), 1_000, "NGN", "to reverse"));

        Long originalEntryId = jdbcTemplate.queryForObject(
                "SELECT id FROM journal_entries WHERE entry_uuid = ?", Long.class, original.transferId());
        var reversal = ledgerService.reverseEntry(originalEntryId, "test");

        List<java.util.Map<String, Object>> reversalRows = jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox_events WHERE journal_entry_id = ?", reversal.getId());
        assertThat(reversalRows).hasSize(1);
        assertThat(reversalRows.get(0).get("event_type")).isEqualTo("REVERSAL_COMPLETED");

        Long originalRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE journal_entry_id = ?", Long.class, originalEntryId);
        assertThat(originalRowCount).isEqualTo(1L); // the original transfer's own row, untouched by the reversal
    }
}
