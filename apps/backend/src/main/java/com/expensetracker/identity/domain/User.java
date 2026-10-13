package com.expensetracker.identity.domain;

import com.expensetracker.shared.error.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "app_users")
public class User {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {}

    private User(String email, String displayName, String passwordHash) {
        this.id = UUID.randomUUID();
        this.email = normalizeEmail(email);
        this.displayName = validateDisplayName(displayName);
        if (passwordHash == null || passwordHash.isBlank() || passwordHash.length() > 255) {
            throw DomainException.invalid("invalid_password_hash");
        }
        this.passwordHash = passwordHash;
        this.enabled = true;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Accepts an already encoded password. Plain passwords belong only in the registration service. */
    public static User create(String email, String displayName, String encodedPassword) {
        return new User(email, displayName, encodedPassword);
    }

    public static String normalizeEmail(String value) {
        if (value == null) {
            throw DomainException.invalid("invalid_email");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw DomainException.invalid("invalid_email");
        }
        return normalized;
    }

    public static String validateDisplayName(String value) {
        if (value == null) {
            throw DomainException.invalid("invalid_display_name");
        }
        String normalized = value.strip();
        if (normalized.isBlank() || normalized.length() > 120
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw DomainException.invalid("invalid_display_name");
        }
        return normalized;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
