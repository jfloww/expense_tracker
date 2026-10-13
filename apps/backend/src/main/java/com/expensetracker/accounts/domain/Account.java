package com.expensetracker.accounts.domain;

import com.expensetracker.shared.error.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountIssuer issuer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountKind kind;

    @Column(name = "last_four", length = 4)
    private String lastFour;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public static Account create(UUID householdId, String name, AccountIssuer issuer,
                                 AccountKind kind, String lastFour, String currency,
                                 Instant now) {
        Account account = new Account();
        if (householdId == null) {
            throw DomainException.invalid("invalid_household_id");
        }
        account.id = UUID.randomUUID();
        account.householdId = householdId;
        account.update(name, issuer, kind, lastFour, currency, true, now);
        account.createdAt = account.updatedAt;
        return account;
    }

    public void update(String name, AccountIssuer issuer, AccountKind kind,
                       String lastFour, String currency, boolean active, Instant now) {
        String normalizedName = name == null ? null : name.strip();
        String normalizedLastFour = lastFour == null ? null : lastFour.strip();
        if (normalizedLastFour != null && normalizedLastFour.isEmpty()) {
            normalizedLastFour = null;
        }
        if (normalizedName == null || normalizedName.isBlank() || normalizedName.length() > 120
                || normalizedName.codePoints().anyMatch(Character::isISOControl)
                || issuer == null || kind == null || !"USD".equals(currency) || now == null
                || (normalizedLastFour != null && !normalizedLastFour.matches("[0-9]{4}"))) {
            throw DomainException.invalid("invalid_account");
        }
        this.name = normalizedName;
        this.issuer = issuer;
        this.kind = kind;
        this.lastFour = normalizedLastFour;
        this.currency = currency;
        this.active = active;
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public String getName() {
        return name;
    }

    public AccountIssuer getIssuer() {
        return issuer;
    }

    public AccountKind getKind() {
        return kind;
    }

    public String getLastFour() {
        return lastFour;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
