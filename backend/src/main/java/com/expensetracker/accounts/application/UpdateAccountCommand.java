package com.expensetracker.accounts.application;

import com.expensetracker.accounts.domain.AccountIssuer;
import com.expensetracker.accounts.domain.AccountKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateAccountCommand(
        @NotBlank @Size(max = 120) String name,
        @NotNull AccountIssuer issuer,
        @NotNull AccountKind kind,
        @Pattern(regexp = "[0-9]{4}") String lastFour,
        @NotNull @Pattern(regexp = "USD") String currency,
        @NotNull Boolean active) {

    public UpdateAccountCommand {
        name = name == null ? null : name.trim();
        lastFour = lastFour == null ? null : lastFour.trim();
        if (lastFour != null && lastFour.isEmpty()) {
            lastFour = null;
        }
    }
}
