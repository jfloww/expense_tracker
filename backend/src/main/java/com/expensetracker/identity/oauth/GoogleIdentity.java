package com.expensetracker.identity.oauth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** Immutable issuer/subject binding; provider email is never an identity key. */
@Entity
@Table(name = "google_identities", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"issuer", "subject"}),
        @UniqueConstraint(columnNames = {"user_id", "issuer"})})
public class GoogleIdentity {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
    @Column(nullable = false, updatable = false, length = 120) private String issuer;
    @Column(nullable = false, updatable = false, length = 255) private String subject;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected GoogleIdentity() {}

    static GoogleIdentity create(UUID userId, String issuer, String subject, Instant createdAt) {
        var identity = new GoogleIdentity();
        identity.id = UUID.randomUUID();
        identity.userId = userId;
        identity.issuer = issuer;
        identity.subject = subject;
        identity.createdAt = createdAt;
        return identity;
    }

    public UUID getUserId() { return userId; }
}
