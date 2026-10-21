package com.ledgercore.reconciliation;

import com.ledgercore.ledger.EntryDirection;

/**
 * One row from either side of the comparison - a ledger line or a bank statement line - in a
 * shared shape. That's what lets {@link MergeJoinReconciler} be generic over where a row came
 * from: it never knows or cares which side produced the row it's looking at.
 *
 * {@code reference} is the ledger's {@code entry_uuid} on the ledger side, and the bank
 * statement's own reference column on the bank side - the two are assumed to be the same value
 * (see the architecture doc's input-contract note). The real match/sort key used by the
 * reconciler is the composite {@code (reference, accountNumber)}, not {@code reference} alone,
 * since one transfer produces two lines - one per account it touches.
 */
public record ReconciliationRow(String reference, String accountNumber, EntryDirection direction,
                                 long amountMinorUnits) {
}
