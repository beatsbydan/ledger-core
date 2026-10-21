package com.ledgercore.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgercore.support.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ReconciliationGuardTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationJob reconciliationJob;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void refusesToStartASecondRunWhileOneIsAlreadyRunning() throws IOException {
        // This row must not outlive the test: the Postgres container is shared across the whole
        // suite (never truncated between classes), and a stuck RUNNING row here would make
        // every reconciliation test that runs afterward see "a run is already in progress" and
        // silently skip, for a reason that has nothing to do with them.
        Long plantedRunId = jdbcTemplate.queryForObject(
                "INSERT INTO reconciliation_runs (run_date, status) VALUES (CURRENT_DATE, 'RUNNING') RETURNING id",
                Long.class);
        try {
            long runCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_runs", Long.class);

            Path file = Files.createTempFile("bank-statement-guard", ".csv");
            Files.write(file, List.of("should-never-be-read,ACC,100,DEBIT"));

            reconciliationJob.run(file);

            long runCountAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_runs", Long.class);
            assertThat(runCountAfter).isEqualTo(runCountBefore); // no second run was started at all
        } finally {
            jdbcTemplate.update("UPDATE reconciliation_runs SET status = 'COMPLETED', completed_at = now() WHERE id = ?",
                    plantedRunId);
        }
    }
}
