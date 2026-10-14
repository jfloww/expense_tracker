package com.expensetracker.accounts.api;

import com.expensetracker.accounts.application.UpdateAccountCommand;
import com.expensetracker.accounts.domain.AccountIssuer;
import com.expensetracker.accounts.domain.AccountKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PUT replaces mutable account fields; active is required and null lastFour clears it. */
public record UpdateAccountRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull AccountIssuer issuer,
        @NotNull AccountKind kind,
        @Pattern(regexp = "[0-9]{4}") String lastFour,
        @NotNull @Pattern(regexp = "USD") String currency,
        @NotNull Boolean active) {

    UpdateAccountCommand command() {
        return new UpdateAccountCommand(name, issuer, kind, lastFour, currency, active);
    }
}
