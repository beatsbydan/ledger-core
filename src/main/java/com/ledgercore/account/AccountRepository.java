package com.ledgercore.account;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    /**
     * Scalar id-only lookup - deliberately not a full entity fetch. Loading the full Account
     * entity here would register it in the session's identity map; a later findByIdForUpdate
     * for the same row would then hand back that already-managed, unlocked instance instead of
     * re-reading it, silently defeating the lock (JPA never refreshes an already-managed entity's
     * state from a repeat query by default).
     */
    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<Long> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    /**
     * Acquires {@code SELECT ... FOR UPDATE} on the account row. Callers must always acquire
     * locks for a transfer's two accounts in ascending id order, regardless of debit/credit role -
     * that fixed order is what prevents the two-account deadlock (see the LedgerCore architecture doc).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
