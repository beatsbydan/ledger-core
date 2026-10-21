package com.ledgercore.reconciliation;

public record ReconciliationCounts(int ledgerRowsScanned, int bankRowsScanned, int mismatchCount) {
}
