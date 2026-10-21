package com.ledgercore.reconciliation;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The Fig. 10 run lifecycle. Not itself @Transactional - it spans multiple independent
 * transactions by design (see {@link ReconciliationExecutor}), plus file I/O that has nothing
 * to do with a database transaction at all.
 */
@Service
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationExecutor executor;
    private final BankStatementReader bankStatementReader;
    private final Path statementFilePath;

    public ReconciliationJob(ReconciliationRunRepository runRepository, ReconciliationExecutor executor,
                              BankStatementReader bankStatementReader,
                              @Value("${ledgercore.reconciliation.statement-file-path}") String statementFilePath) {
        this.runRepository = runRepository;
        this.executor = executor;
        this.bankStatementReader = bankStatementReader;
        this.statementFilePath = Path.of(statementFilePath);
    }

    @Scheduled(cron = "${ledgercore.reconciliation.cron:0 0 2 * * *}")
    public void run() {
        run(statementFilePath);
    }

    /** Exposed with an explicit path so tests can point at a per-test file without waiting on the cron schedule. */
    public void run(Path statementFilePath) {
        if (runRepository.existsByStatus(ReconciliationStatus.RUNNING)) {
            log.warn("Skipping reconciliation trigger - a run is already RUNNING");
            return;
        }

        LocalDate today = LocalDate.now();
        Stream<ReconciliationRow> bankRows;
        try {
            bankRows = bankStatementReader.stream(statementFilePath);
        } catch (UncheckedIOException e) {
            Long runId = executor.recordFailedBeforeStarting(today,
                    "Could not open bank statement file: " + e.getMessage());
            log.error("Reconciliation run {} failed before starting: {}", runId, e.getMessage());
            return;
        }

        Long runId = executor.startRun(today);
        try {
            executor.executeRun(runId, bankRows);
        } finally {
            bankRows.close();
        }
    }
}
