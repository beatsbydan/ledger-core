package com.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

class JournalInvariantTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Account newAccount(String number, long opening) {
        return accountRepository.save(new Account(number, "Owner " + number, AccountType.CUSTOMER, "NGN", opening));
    }

    @Test
    void balancedEntryCommitsSuccessfully() {
        Account a = newAccount("JIT-A-" + System.nanoTime(), 10_000);
        Account b = newAccount("JIT-B-" + System.nanoTime(), 0);

        JournalEntry entry = ledgerService.appendEntry(
                JournalEntryType.TRANSFER, "NGN", "test transfer", "test",
                List.of(new JournalLine(a, EntryDirection.DEBIT, 1_000),
                        new JournalLine(b, EntryDirection.CREDIT, 1_000)));

        assertThat(entry.getId()).isNotNull();
        assertThat(entry.getLines()).hasSize(2);
    }

    @Test
    void unbalancedLineIsRejectedByDeferredConstraintTrigger() {
        Account a = newAccount("JIT-C-" + System.nanoTime(), 10_000);

        Long entryId = jdbcTemplate.queryForObject(
                "INSERT INTO journal_entries (entry_type, currency) VALUES ('ADJUSTMENT', 'NGN') RETURNING id",
                Long.class);

        // A lone DEBIT line with no offsetting CREDIT - sum is non-zero, must be rejected.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO journal_lines (journal_entry_id, account_id, direction, amount_minor_units, line_number) "
                        + "VALUES (?, ?, 'DEBIT', 500, 1)",
                entryId, a.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void journalLinesAreImmutable() {
        Account a = newAccount("JIT-D-" + System.nanoTime(), 10_000);
        Account b = newAccount("JIT-E-" + System.nanoTime(), 0);
        JournalEntry entry = ledgerService.appendEntry(
                JournalEntryType.TRANSFER, "NGN", "immutability check", "test",
                List.of(new JournalLine(a, EntryDirection.DEBIT, 500),
                        new JournalLine(b, EntryDirection.CREDIT, 500)));
        Long lineId = entry.getLines().get(0).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_lines SET amount_minor_units = amount_minor_units + 1 WHERE id = ?", lineId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM journal_entries WHERE id = ?", entry.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void reverseEntryCreatesNewEntryWithoutTouchingOriginal() {
        Account a = newAccount("JIT-F-" + System.nanoTime(), 10_000);
        Account b = newAccount("JIT-G-" + System.nanoTime(), 0);
        // appendEntry only writes the journal - applying the balance change is the caller's
        // job (normally LedgerTransferExecutor's). Simulate that here, same as a real transfer would.
        a.applyDebit(750);
        b.applyCredit(750);
        JournalEntry original = ledgerService.appendEntry(
                JournalEntryType.TRANSFER, "NGN", "to be reversed", "test",
                List.of(new JournalLine(a, EntryDirection.DEBIT, 750),
                        new JournalLine(b, EntryDirection.CREDIT, 750)));

        JournalEntry reversal = ledgerService.reverseEntry(original.getId(), "test");

        assertThat(reversal.getEntryType()).isEqualTo(JournalEntryType.REVERSAL);
        assertThat(reversal.getReversedEntry().getId()).isEqualTo(original.getId());
        assertThat(reversal.getLines()).hasSize(2);
        assertThat(accountRepository.findById(a.getId()).orElseThrow().getBalanceMinorUnits()).isEqualTo(10_000);
        assertThat(accountRepository.findById(b.getId()).orElseThrow().getBalanceMinorUnits()).isEqualTo(0);

        JournalEntry originalReloaded = journalEntryRepository.findById(original.getId()).orElseThrow();
        assertThat(originalReloaded.getEntryType()).isEqualTo(JournalEntryType.TRANSFER);
        assertThat(originalReloaded.getLines()).hasSize(2);
    }
}
