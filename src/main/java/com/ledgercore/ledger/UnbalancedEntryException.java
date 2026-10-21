package com.ledgercore.ledger;

/**
 * App-level fast-fail check. The deferred DB constraint trigger on journal_lines is the
 * authoritative backstop for this invariant - this exception exists to fail cheaply and
 * clearly before paying for a DB round-trip that would be rejected anyway.
 */
public class UnbalancedEntryException extends RuntimeException {

    public UnbalancedEntryException(long sum) {
        super("Journal entry is not balanced: debit/credit sum = " + sum + ", expected 0");
    }
}
