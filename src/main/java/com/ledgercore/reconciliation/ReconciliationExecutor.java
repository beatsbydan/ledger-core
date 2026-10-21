package com.ledgercore.reconciliation;

import java.time.LocalDate;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The @Transactional pieces of a reconciliation run, split out from {@link ReconciliationJob}
 * deliberately - calling these from within the same class via {@code this.} would bypass
 * Spring's transactional proxy entirely (self-invocation), the same bug already found and fixed
 * once this session for IdempotencyService/IdempotencyKeyStore.
 *
 * startRun and recordFailedBeforeStarting each commit in their own REQUIRES_NEW transaction, so
 * a RUNNING row is durable - visible to the next trigger's guard check - the instant the run
 * actually starts, independent of whether executeRun later succeeds or rolls back.
 */
@Component
class ReconciliationExecutor {

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationMismatchRepository mismatchRepository;
    private final LedgerReconciliationRepository ledgerRepository;
    private final MergeJoinReconciler reconciler;

    ReconciliationExecutor(ReconciliationRunRepository runRepository,
                            ReconciliationMismatchRepository mismatchRepository,
                            LedgerReconciliationRepository ledgerRepository,
                            MergeJoinReconciler reconciler) {
        this.runRepository = runRepository;
        this.mismatchRepository = mismatchRepository;
        this.ledgerRepository = ledgerRepository;
        this.reconciler = reconciler;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Long startRun(LocalDate runDate) {
        return runRepository.save(ReconciliationRun.starting(runDate)).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Long recordFailedBeforeStarting(LocalDate runDate, String reason) {
        return runRepository.save(ReconciliationRun.failedBeforeStarting(runDate, reason)).getId();
    }

    /**
     * One transaction for the whole run: the ledger stream is a live cursor that must stay open
     * for exactly this method's duration, and mismatches are written on the same connection as
     * they're found rather than accumulated - there's no known upper bound on mismatch count
     * going in.
     */
    @Transactional
    void executeRun(Long runId, Stream<ReconciliationRow> bankRows) {
        try (Stream<ReconciliationRow> ledgerRows = ledgerRepository.streamForReconciliation()) {
            ReconciliationCounts counts = reconciler.reconcile(
                    ledgerRows.iterator(),
                    bankRows.iterator(),
                    mismatch -> mismatchRepository.save(new ReconciliationMismatch(runId, mismatch)));

            ReconciliationRun run = runRepository.findById(runId)
                    .orElseThrow(() -> new IllegalStateException("Reconciliation run disappeared: " + runId));
            run.complete(counts.ledgerRowsScanned(), counts.bankRowsScanned(), counts.mismatchCount());
        }
    }
}
