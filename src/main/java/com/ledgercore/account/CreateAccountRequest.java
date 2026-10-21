package com.ledgercore.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank String accountNumber,
        @NotBlank String ownerName,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @PositiveOrZero Long openingBalanceMinorUnits
) {
    public long openingBalanceOrZero() {
        return openingBalanceMinorUnits == null ? 0L : openingBalanceMinorUnits;
    }
}
