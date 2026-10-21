package com.ledgercore.common;

import com.ledgercore.account.AccountNotActiveException;
import com.ledgercore.account.AccountNotFoundException;
import com.ledgercore.account.DuplicateAccountNumberException;
import com.ledgercore.idempotency.IdempotencyConflictException;
import com.ledgercore.idempotency.IdempotencyInProgressException;
import com.ledgercore.ledger.UnbalancedEntryException;
import com.ledgercore.transfer.InsufficientFundsException;
import com.ledgercore.transfer.InvalidTransferException;
import com.ledgercore.transfer.TransferNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransfer(InvalidTransferException ex, HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_TRANSFER", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("Validation failed");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, req);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex, HttpServletRequest req) {
        return body(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage(), req);
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransferNotFound(TransferNotFoundException ex, HttpServletRequest req) {
        return body(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", ex.getMessage(), req);
    }

    @ExceptionHandler(DuplicateAccountNumberException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateAccount(DuplicateAccountNumberException ex, HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", ex.getMessage(), req);
    }

    @ExceptionHandler(AccountNotActiveException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotActive(AccountNotActiveException ex, HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "ACCOUNT_NOT_ACTIVE", ex.getMessage(), req);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", ex.getMessage(), req);
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyInProgress(IdempotencyInProgressException ex, HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", ex.getMessage(), req);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex, HttpServletRequest req) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", ex.getMessage(), req);
    }

    @ExceptionHandler(UnbalancedEntryException.class)
    public ResponseEntity<ErrorResponse> handleUnbalancedEntry(UnbalancedEntryException ex, HttpServletRequest req) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "UNBALANCED_ENTRY", ex.getMessage(), req);
    }

    private ResponseEntity<ErrorResponse> body(HttpStatus status, String error, String message, HttpServletRequest req) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), error, message, req.getRequestURI()));
    }
}
