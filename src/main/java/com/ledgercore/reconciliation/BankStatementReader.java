package com.ledgercore.reconciliation;

import com.ledgercore.ledger.EntryDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the simulated bank statement as a lazy, line-at-a-time stream - {@code Files.lines}
 * already never loads the whole file into memory - and parses each line into the same
 * {@link ReconciliationRow} shape the ledger side uses. No CSV library: the file is
 * simulated/internal, not user-uploaded, so a plain comma-split is proportionate - a documented
 * simplifying assumption, same spirit as the architecture doc's {@code reference == entry_uuid}
 * assumption.
 *
 * A malformed line is logged and filtered out of the stream, never thrown - one bad row must
 * never abort the whole run.
 */
@Component
public class BankStatementReader {

    private static final Logger log = LoggerFactory.getLogger(BankStatementReader.class);

    /** Throws immediately (not lazily) if the file can't be opened - lets the caller distinguish a missing file before starting a run. */
    public Stream<ReconciliationRow> stream(Path file) {
        try {
            return Files.lines(file)
                    .filter(line -> !line.isBlank())
                    .map(this::parseLine)
                    .filter(Optional::isPresent)
                    .map(Optional::get);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open bank statement file: " + file, e);
        }
    }

    private Optional<ReconciliationRow> parseLine(String line) {
        String[] fields = line.split(",", -1);
        if (fields.length != 4) {
            log.warn("Skipping malformed bank statement line (expected 4 fields, got {}): {}", fields.length, line);
            return Optional.empty();
        }
        try {
            String reference = fields[0].trim();
            String accountNumber = fields[1].trim();
            long amount = Long.parseLong(fields[2].trim());
            EntryDirection direction = EntryDirection.valueOf(fields[3].trim().toUpperCase());
            return Optional.of(new ReconciliationRow(reference, accountNumber, direction, amount));
        } catch (RuntimeException e) {
            log.warn("Skipping malformed bank statement line: {} ({})", line, e.toString());
            return Optional.empty();
        }
    }
}
