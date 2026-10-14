package com.expensetracker.accounts.api;

import com.expensetracker.accounts.application.CreateAccountCommand;
import com.expensetracker.accounts.domain.AccountIssuer;
import com.expensetracker.accounts.domain.AccountKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull AccountIssuer issuer,
        @NotNull AccountKind kind,
        @Pattern(regexp = "[0-9]{4}") String lastFour,
        @NotNull @Pattern(regexp = "USD") String currency) {

    CreateAccountCommand command() {
        return new CreateAccountCommand(name, issuer, kind, lastFour, currency);
    }
}
