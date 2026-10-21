package com.ledgercore.reconciliation;

import com.ledgercore.ledger.JournalLine;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import jakarta.persistence.QueryHint;

/**
 * Not a full CRUD repository - deliberately just {@code Repository<JournalLine, Long>} with one
 * method, since this exists purely for the reconciliation job's read, not general ledger access.
 *
 * The fetch-size hint is what actually makes {@code streamForReconciliation} a server-side
 * cursor rather than Hibernate quietly materializing the whole result set before handing back a
 * Stream wrapper - it's the concrete mechanism behind "constant memory," not just a nice-to-have.
 * Callers MUST keep the surrounding transaction open for the full lifetime of the returned
 * Stream (it's a live cursor, not a detached collection) and close the Stream itself - it holds
 * JDBC resources.
 */
public interface LedgerReconciliationRepository extends Repository<JournalLine, Long> {

    @QueryHints(@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "500"))
    @Query("select new com.ledgercore.reconciliation.ReconciliationRow("
            + "cast(e.entryUuid as string), a.accountNumber, l.direction, l.amountMinorUnits) "
            + "from JournalLine l join l.journalEntry e join l.account a "
            + "order by e.entryUuid, a.accountNumber")
    Stream<ReconciliationRow> streamForReconciliation();
}
