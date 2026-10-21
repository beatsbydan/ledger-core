package com.ledgercore.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgercore.account.AccountNotActiveException;
import com.ledgercore.account.AccountNotFoundException;
import com.ledgercore.idempotency.ClaimResult;
import com.ledgercore.idempotency.IdempotencyService;
import com.ledgercore.ledger.JournalEntry;
import com.ledgercore.ledger.JournalEntryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the three independent transactions behind an idempotent transfer - claim,
 * the locked transfer itself, then finalize. Deliberately not @Transactional: it spans three
 * separate commits by design, not one. See the LedgerCore architecture doc's idempotency flow.
 *
 * Known Phase 1 limitation (documented, accepted): a crash between the transfer committing and
 * the finalize step recording it can leave an orphaned IN_PROGRESS idempotency record even
 * though money moved. The staleness-based reclaim in IdempotencyService bounds the damage but
 * doesn't fully close this window - closing it needs checking for an existing journal entry
 * before reclaiming, which is Phase 2 hardening, not Phase 1 scope.
 */
@Service
public class TransferService {

    private final IdempotencyService idempotencyService;
    private final LedgerTransferExecutor executor;
    private final JournalEntryRepository journalEntryRepository;
    private final ObjectMapper objectMapper;

    public TransferService(IdempotencyService idempotencyService, LedgerTransferExecutor executor,
                            JournalEntryRepository journalEntryRepository, ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.executor = executor;
        this.journalEntryRepository = journalEntryRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transferId) {
        JournalEntry entry = journalEntryRepository.findByEntryUuid(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
        return TransferResponse.from(entry);
    }

    public TransferResponse transfer(String idempotencyKey, TransferRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidTransferException("Idempotency-Key header is required");
        }

        String fingerprint = fingerprint(request);
        ClaimResult claim = idempotencyService.claim(idempotencyKey, fingerprint);

        if (claim instanceof ClaimResult.Replay replay) {
            return readJson(replay.responseBody());
        }

        long recordId = ((ClaimResult.NewClaim) claim).recordId();
        try {
            JournalEntry entry = executor.executeTransfer(TransferCommand.from(request));
            TransferResponse response = TransferResponse.completed(entry, request);
            idempotencyService.complete(recordId, HttpStatus.CREATED.value(), writeJson(response), entry.getId());
            return response;
        } catch (RuntimeException ex) {
            idempotencyService.fail(recordId, statusFor(ex).value(), writeJson(new FailureBody(ex.getMessage())));
            throw ex;
        }
    }

    private HttpStatus statusFor(RuntimeException ex) {
        return switch (ex) {
            case InvalidTransferException e -> HttpStatus.BAD_REQUEST;
            case AccountNotFoundException e -> HttpStatus.NOT_FOUND;
            case AccountNotActiveException e -> HttpStatus.CONFLICT;
            case InsufficientFundsException e -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private String fingerprint(TransferRequest request) {
        String raw = request.sourceAccountNumber() + "|" + request.destinationAccountNumber() + "|"
                + request.amountMinorUnits() + "|" + request.currency();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotency response body", e);
        }
    }

    private TransferResponse readJson(String json) {
        try {
            return objectMapper.readValue(json, TransferResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached idempotency response", e);
        }
    }

    private record FailureBody(String message) {
    }
}
