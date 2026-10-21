package com.ledgercore.transfer;

/** Internal command passed to {@link LedgerTransferExecutor} - decoupled from the REST DTO on purpose. */
public record TransferCommand(
        String sourceAccountNumber,
        String destinationAccountNumber,
        long amountMinorUnits,
        String currency,
        String description
) {
    public static TransferCommand from(TransferRequest request) {
        return new TransferCommand(
                request.sourceAccountNumber(),
                request.destinationAccountNumber(),
                request.amountMinorUnits(),
                request.currency(),
                request.description()
        );
    }
}
