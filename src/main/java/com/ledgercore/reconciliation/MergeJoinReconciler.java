package com.ledgercore.reconciliation;

import java.util.Comparator;
import java.util.Iterator;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * The Fig. 11 algorithm, exactly: two cursors over already-sorted streams, advancing whichever
 * side is behind (and flagging that row) until the keys match, at which point both advance
 * together. Once either side runs out, every remaining row on the other side is its own
 * mismatch - no further comparison needed.
 *
 * Deliberately pure - no Spring beans beyond this one stateless component, no I/O, no database,
 * no file access. That's what makes every branch here directly testable against plain in-memory
 * iterators, independent of whether the rows actually came from Postgres or a file.
 *
 * The match/sort key is the composite (reference, accountNumber), not reference alone: one
 * journal entry produces two lines - one per account it touches - so reference by itself does
 * not uniquely identify a row to compare.
 */
@Component
public class MergeJoinReconciler {

    private static final Comparator<ReconciliationRow> KEY_ORDER =
            Comparator.comparing(ReconciliationRow::reference).thenComparing(ReconciliationRow::accountNumber);

    public ReconciliationCounts reconcile(Iterator<ReconciliationRow> ledgerRows, Iterator<ReconciliationRow> bankRows,
                                           Consumer<Mismatch> onMismatch) {
        int ledgerCount = 0;
        int bankCount = 0;
        int mismatchCount = 0;

        ReconciliationRow ledgerRow = nextOrNull(ledgerRows);
        if (ledgerRow != null) {
            ledgerCount++;
        }
        ReconciliationRow bankRow = nextOrNull(bankRows);
        if (bankRow != null) {
            bankCount++;
        }

        while (ledgerRow != null && bankRow != null) {
            int comparison = KEY_ORDER.compare(ledgerRow, bankRow);
            if (comparison < 0) {
                onMismatch.accept(Mismatch.ledgerOnly(ledgerRow));
                mismatchCount++;
                ledgerRow = nextOrNull(ledgerRows);
                if (ledgerRow != null) {
                    ledgerCount++;
                }
            } else if (comparison > 0) {
                onMismatch.accept(Mismatch.bankOnly(bankRow));
                mismatchCount++;
                bankRow = nextOrNull(bankRows);
                if (bankRow != null) {
                    bankCount++;
                }
            } else {
                if (ledgerRow.amountMinorUnits() != bankRow.amountMinorUnits()) {
                    onMismatch.accept(Mismatch.amountMismatch(ledgerRow, bankRow));
                    mismatchCount++;
                }
                ledgerRow = nextOrNull(ledgerRows);
                if (ledgerRow != null) {
                    ledgerCount++;
                }
                bankRow = nextOrNull(bankRows);
                if (bankRow != null) {
                    bankCount++;
                }
            }
        }

        while (ledgerRow != null) {
            onMismatch.accept(Mismatch.ledgerOnly(ledgerRow));
            mismatchCount++;
            ledgerRow = nextOrNull(ledgerRows);
            if (ledgerRow != null) {
                ledgerCount++;
            }
        }
        while (bankRow != null) {
            onMismatch.accept(Mismatch.bankOnly(bankRow));
            mismatchCount++;
            bankRow = nextOrNull(bankRows);
            if (bankRow != null) {
                bankCount++;
            }
        }

        return new ReconciliationCounts(ledgerCount, bankCount, mismatchCount);
    }

    private ReconciliationRow nextOrNull(Iterator<ReconciliationRow> iterator) {
        return iterator.hasNext() ? iterator.next() : null;
    }
}
