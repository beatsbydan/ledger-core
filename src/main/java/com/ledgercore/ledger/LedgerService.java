package com.ledgercore.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgercore.account.Account;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.outbox.OutboxEvent;
import com.ledgercore.outbox.OutboxEventRepository;
import com.ledgercore.outbox.OutboxPayload;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic "append a balanced journal entry" operation, plus reversal. Does not decide
 * whether accounts need locking for a given caller - {@code reverseEntry} locks the accounts
 * it touches itself (ascending id order, same rule as every other multi-account mutation);
 * {@code appendEntry} assumes the caller (e.g. the transfer executor) already holds whatever
 * locks it needs and has already applied the corresponding balance changes to the account
 * entities referenced by the given lines.
 *
 * Every entry written here - transfer or reversal - also writes exactly one outbox event, in
 * the same transaction and the same commit as the journal rows. That's deliberate: it means no
 * caller of appendEntry/reverseEntry has to remember to emit an event separately, and the event
 * can never exist without the journal entry it describes, or vice versa.
 */
@Service
public class LedgerService {

    private final JournalEntryRepository journalEntryRepository;
    private final AccountRepository accountRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final String kafkaTopic;

    public LedgerService(JournalEntryRepository journalEntryRepository, AccountRepository accountRepository,
                          OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper,
                          @Value("${ledgercore.kafka.topic}") String kafkaTopic) {
        this.journalEntryRepository = journalEntryRepository;
        this.accountRepository = accountRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.kafkaTopic = kafkaTopic;
    }

    @Transactional
    public JournalEntry appendEntry(JournalEntryType type, String currency, String description,
                                     String createdBy, List<JournalLine> lines) {
        JournalEntry entry = new JournalEntry(type, currency, description, null, createdBy);
        lines.forEach(entry::addLine);
        return save(entry);
    }

    /**
     * Writes a new entry with every line's direction flipped relative to {@code originalEntryId},
     * applies the corresponding balance change to each account involved, and leaves the original
     * entry untouched - journal rows are immutable, corrections only ever add a new row.
     */
    @Transactional
    public JournalEntry reverseEntry(Long originalEntryId, String createdBy) {
        JournalEntry original = journalEntryRepository.findById(originalEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal entry not found: " + originalEntryId));
        List<JournalLine> originalLines = original.getLines();

        Map<Long, Account> lockedAccounts = lockAccountsAscending(originalLines);

        JournalEntry reversal = new JournalEntry(
                JournalEntryType.REVERSAL,
                original.getCurrency(),
                "Reversal of entry " + original.getEntryUuid(),
                original,
                createdBy
        );

        for (JournalLine originalLine : originalLines) {
            Account account = lockedAccounts.get(originalLine.getAccount().getId());
            EntryDirection reversedDirection = originalLine.getDirection().opposite();
            long amount = originalLine.getAmountMinorUnits();

            if (reversedDirection == EntryDirection.CREDIT) {
                account.applyCredit(amount);
            } else {
                account.applyDebit(amount);
            }
            reversal.addLine(new JournalLine(account, reversedDirection, amount));
        }

        return save(reversal);
    }

    private Map<Long, Account> lockAccountsAscending(List<JournalLine> lines) {
        var accountIds = new TreeSet<Long>();
        lines.forEach(line -> accountIds.add(line.getAccount().getId()));

        Map<Long, Account> locked = new LinkedHashMap<>();
        for (Long accountId : accountIds) {
            Account account = accountRepository.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new IllegalStateException("Account disappeared during reversal: " + accountId));
            locked.put(accountId, account);
        }
        return locked;
    }

    private JournalEntry save(JournalEntry entry) {
        long sum = entry.getLines().stream()
                .mapToLong(l -> l.getDirection() == EntryDirection.DEBIT ? l.getAmountMinorUnits() : -l.getAmountMinorUnits())
                .sum();
        if (sum != 0) {
            throw new UnbalancedEntryException(sum);
        }
        JournalEntry saved = journalEntryRepository.save(entry);
        outboxEventRepository.save(buildOutboxEvent(saved));
        return saved;
    }

    private OutboxEvent buildOutboxEvent(JournalEntry entry) {
        List<OutboxPayload.LinePayload> linePayloads = entry.getLines().stream()
                .map(l -> new OutboxPayload.LinePayload(
                        l.getAccount().getAccountNumber(), l.getDirection().name(), l.getAmountMinorUnits()))
                .toList();
        OutboxPayload payload = new OutboxPayload(
                entry.getEntryUuid(), entry.getEntryType().name(), entry.getCurrency(), linePayloads, Instant.now());

        String eventType = entry.getEntryType().name() + "_COMPLETED";
        return new OutboxEvent(entry.getId(), eventType, writeJson(payload), kafkaTopic);
    }

    private String writeJson(OutboxPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
