package com.expensetracker.households.domain;

import com.expensetracker.shared.error.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "households")
public class Household {
    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Household() {}

    private Household(String name) {
        this.id = UUID.randomUUID();
        this.name = validateName(name);
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static Household create(String name) {
        return new Household(name);
    }

    public static String validateName(String value) {
        if (value == null) {
            throw DomainException.invalid("invalid_household_name");
        }
        String normalized = value.strip();
        if (normalized.isBlank() || normalized.length() > 120
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw DomainException.invalid("invalid_household_name");
        }
        return normalized;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}
