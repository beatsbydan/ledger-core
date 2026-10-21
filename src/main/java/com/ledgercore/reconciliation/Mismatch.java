package com.ledgercore.reconciliation;

/** Emitted by {@link MergeJoinReconciler} as it finds mismatches - persisted immediately by the caller, not accumulated. */
public record Mismatch(MismatchType type, String entryUuid, String bankReference, String accountNumber,
                        Long ledgerAmountMinorUnits, Long bankAmountMinorUnits) {

    public static Mismatch ledgerOnly(ReconciliationRow ledgerRow) {
        return new Mismatch(MismatchType.LEDGER_ONLY, ledgerRow.reference(), null,
                ledgerRow.accountNumber(), ledgerRow.amountMinorUnits(), null);
    }

    public static Mismatch bankOnly(ReconciliationRow bankRow) {
        return new Mismatch(MismatchType.BANK_ONLY, null, bankRow.reference(),
                bankRow.accountNumber(), null, bankRow.amountMinorUnits());
    }

    public static Mismatch amountMismatch(ReconciliationRow ledgerRow, ReconciliationRow bankRow) {
        return new Mismatch(MismatchType.AMOUNT_MISMATCH, ledgerRow.reference(), bankRow.reference(),
                ledgerRow.accountNumber(), ledgerRow.amountMinorUnits(), bankRow.amountMinorUnits());
    }
}
