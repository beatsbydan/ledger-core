package com.ledgercore.transfer;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountNotActiveException;
import com.ledgercore.account.AccountNotFoundException;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.ledger.EntryDirection;
import com.ledgercore.ledger.JournalEntry;
import com.ledgercore.ledger.JournalEntryType;
import com.ledgercore.ledger.JournalLine;
import com.ledgercore.ledger.LedgerService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one locked transaction in the transfer flow. Pre-lock checks (both accounts exist,
 * source != destination) run first and cost nothing; once both accounts are locked, the
 * transfer either fully commits or fully rolls back - no partial state is possible.
 *
 * Both accounts are always locked in ascending-id order, regardless of which one is the
 * debit side or the credit side for this particular transfer. That fixed order - not which
 * role an account happens to play - is what makes the two-account deadlock structurally
 * impossible: two transfers racing in opposite directions between the same pair always
 * request the lower id first, so neither can end up holding the lock the other is waiting on.
 */
@Service
public class LedgerTransferExecutor {

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;

    public LedgerTransferExecutor(AccountRepository accountRepository, LedgerService ledgerService) {
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public JournalEntry executeTransfer(TransferCommand command) {
        Long sourceId = accountRepository.findIdByAccountNumber(command.sourceAccountNumber())
                .orElseThrow(() -> new AccountNotFoundException(command.sourceAccountNumber()));
        Long destinationId = accountRepository.findIdByAccountNumber(command.destinationAccountNumber())
                .orElseThrow(() -> new AccountNotFoundException(command.destinationAccountNumber()));

        if (sourceId.equals(destinationId)) {
            throw new InvalidTransferException("source and destination accounts must differ");
        }

        Map<Long, Account> locked = lockAscending(sourceId, destinationId);
        Account source = locked.get(sourceId);
        Account destination = locked.get(destinationId);

        if (!source.isActive()) {
            throw new AccountNotActiveException(source.getAccountNumber(), source.getStatus());
        }
        if (!destination.isActive()) {
            throw new AccountNotActiveException(destination.getAccountNumber(), destination.getStatus());
        }
        if (source.getBalanceMinorUnits() < command.amountMinorUnits()) {
            throw new InsufficientFundsException(source.getAccountNumber(), command.amountMinorUnits(),
                    source.getBalanceMinorUnits());
        }

        source.applyDebit(command.amountMinorUnits());
        destination.applyCredit(command.amountMinorUnits());

        JournalLine debitLine = new JournalLine(source, EntryDirection.DEBIT, command.amountMinorUnits());
        JournalLine creditLine = new JournalLine(destination, EntryDirection.CREDIT, command.amountMinorUnits());

        return ledgerService.appendEntry(JournalEntryType.TRANSFER, command.currency(), command.description(),
                "transfer-api", List.of(debitLine, creditLine));
    }

    private Map<Long, Account> lockAscending(Long id1, Long id2) {
        Map<Long, Account> locked = new LinkedHashMap<>();
        for (Long id : new TreeSet<>(List.of(id1, id2))) {
            Account account = accountRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalStateException("Account disappeared during transfer: " + id));
            locked.put(id, account);
        }
        return locked;
    }
}
