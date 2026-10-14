package com.expensetracker.accounts.application;

import com.expensetracker.accounts.domain.Account;
import com.expensetracker.accounts.domain.AccountIssuer;
import com.expensetracker.accounts.domain.AccountKind;
import java.time.Instant;
import java.util.UUID;

public record AccountView(UUID id, UUID householdId, String name, AccountIssuer issuer,
                          AccountKind kind, String lastFour, String currency, boolean active,
                          Instant createdAt, Instant updatedAt) {
    public static AccountView from(Account account) {
        return new AccountView(account.getId(), account.getHouseholdId(), account.getName(),
                account.getIssuer(), account.getKind(), account.getLastFour(),
                account.getCurrency(), account.isActive(), account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
