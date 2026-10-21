package com.ledgercore.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgercore.ledger.EntryDirection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure, in-memory, no Spring and no I/O - every branch of Fig. 11 exercised directly against
 * hand-built sorted lists. This is deliberately independent of {@link ReconciliationJobTest},
 * which proves the same algorithm works end-to-end against real Postgres/file streams.
 */
class ReconciliationMergeJoinTest {

    private final MergeJoinReconciler reconciler = new MergeJoinReconciler();

    private static ReconciliationRow row(String reference, String account, long amount) {
        return new ReconciliationRow(reference, account, EntryDirection.DEBIT, amount);
    }

    @Test
    void matchingRowsWithEqualAmountsProduceNoMismatch() {
        List<ReconciliationRow> ledger = List.of(row("e1", "A", 100), row("e2", "B", 200));
        List<ReconciliationRow> bank = List.of(row("e1", "A", 100), row("e2", "B", 200));
        List<Mismatch> found = new ArrayList<>();

        ReconciliationCounts counts = reconciler.reconcile(ledger.iterator(), bank.iterator(), found::add);

        assertThat(found).isEmpty();
        assertThat(counts).isEqualTo(new ReconciliationCounts(2, 2, 0));
    }

    @Test
    void matchingKeyWithDifferentAmountIsFlaggedAmountMismatch() {
        List<ReconciliationRow> ledger = List.of(row("e1", "A", 100));
        List<ReconciliationRow> bank = List.of(row("e1", "A", 999));
        List<Mismatch> found = new ArrayList<>();

        reconciler.reconcile(ledger.iterator(), bank.iterator(), found::add);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(MismatchType.AMOUNT_MISMATCH);
        assertThat(found.get(0).ledgerAmountMinorUnits()).isEqualTo(100);
        assertThat(found.get(0).bankAmountMinorUnits()).isEqualTo(999);
    }

    @Test
    void ledgerRowWithNoBankCounterpartInBetweenIsFlaggedLedgerOnly() {
        // ledger has e3 that bank skips straight over - bank jumps from e1 to e5
        List<ReconciliationRow> ledger = List.of(row("e1", "A", 100), row("e3", "A", 50), row("e5", "A", 10));
        List<ReconciliationRow> bank = List.of(row("e1", "A", 100), row("e5", "A", 10));
        List<Mismatch> found = new ArrayList<>();

        ReconciliationCounts counts = reconciler.reconcile(ledger.iterator(), bank.iterator(), found::add);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(MismatchType.LEDGER_ONLY);
        assertThat(found.get(0).entryUuid()).isEqualTo("e3");
        assertThat(counts).isEqualTo(new ReconciliationCounts(3, 2, 1));
    }

    @Test
    void bankRowWithNoLedgerCounterpartInBetweenIsFlaggedBankOnly() {
        List<ReconciliationRow> ledger = List.of(row("e1", "A", 100), row("e5", "A", 10));
        List<ReconciliationRow> bank = List.of(row("e1", "A", 100), row("e3", "A", 50), row("e5", "A", 10));
        List<Mismatch> found = new ArrayList<>();

        reconciler.reconcile(ledger.iterator(), bank.iterator(), found::add);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(MismatchType.BANK_ONLY);
        assertThat(found.get(0).bankReference()).isEqualTo("e3");
    }

    @Test
    void exhaustedBankSideDrainsRemainingLedgerRowsAsLedgerOnly() {
        List<ReconciliationRow> ledger = List.of(row("e1", "A", 100), row("e2", "A", 50), row("e3", "A", 10));
        List<ReconciliationRow> bank = List.of(row("e1", "A", 100));
        List<Mismatch> found = new ArrayList<>();

        ReconciliationCounts counts = reconciler.reconcile(ledger.iterator(), bank.iterator(), found::add);

        assertThat(found).hasSize(2);
        assertThat(found).allSatisfy(m -> assertThat(m.type()).isEqualTo(MismatchType.LEDGER_ONLY));
        assertThat(found).extracting(Mismatch::entryUuid).containsExactly("e2", "e3");
        assertThat(counts).isEqualTo(new ReconciliationCounts(3, 1, 2));
    }

    @Test
    void exhaustedLedgerSideDrainsRemainingBankRowsAsBankOnly() {
        List<ReconciliationRow> ledger = List.of(row("e1", "A", 100));
        List<ReconciliationRow> bank = List.of(row("e1", "A", 100), row("e2", "A", 50), row("e3", "A", 10));
        List<Mismatch> found = new ArrayList<>();

        ReconciliationCounts counts = reconciler.reconcile(ledger.iterator(), bank.iterator(), found::add);

        assertThat(found).hasSize(2);
        assertThat(found).allSatisfy(m -> assertThat(m.type()).isEqualTo(MismatchType.BANK_ONLY));
        assertThat(found).extracting(Mismatch::bankReference).containsExactly("e2", "e3");
        assertThat(counts).isEqualTo(new ReconciliationCounts(1, 3, 2));
    }

    @Test
    void sameReferenceDifferentAccountsAreNotTreatedAsAMatch() {
        // same entry_uuid, two different legs (debit account A, credit account B) - the composite
        // key must distinguish them, or this test would wrongly see them as one matched row.
        List<ReconciliationRow> ledger = List.of(row("e1", "A", 100), row("e1", "B", 100));
        List<ReconciliationRow> bank = List.of(row("e1", "A", 100));
        List<Mismatch> found = new ArrayList<>();

        reconciler.reconcile(ledger.iterator(), bank.iterator(), found::add);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).type()).isEqualTo(MismatchType.LEDGER_ONLY);
        assertThat(found.get(0).accountNumber()).isEqualTo("B");
    }

    @Test
    void bothSidesEmptyProducesNoMismatchesAndZeroCounts() {
        List<Mismatch> found = new ArrayList<>();

        ReconciliationCounts counts = reconciler.reconcile(
                List.<ReconciliationRow>of().iterator(), List.<ReconciliationRow>of().iterator(), found::add);

        assertThat(found).isEmpty();
        assertThat(counts).isEqualTo(new ReconciliationCounts(0, 0, 0));
    }
}
