package com.ledgercore.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotBlank String sourceAccountNumber,
        @NotBlank String destinationAccountNumber,
        @Positive long amountMinorUnits,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Size(max = 500) String description
) {
}
