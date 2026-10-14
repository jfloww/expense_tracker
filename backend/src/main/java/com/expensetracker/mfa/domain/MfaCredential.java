package com.expensetracker.mfa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mfa_credentials")
public class MfaCredential {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "secret_ciphertext", length = 512)
    private String secretCiphertext;

    @Column(name = "pending_secret_ciphertext", length = 512)
    private String pendingSecretCiphertext;

    @Column(name = "enrollment_expires_at")
    private Instant enrollmentExpiresAt;

    @Column(name = "enrollment_auth_version")
    private Long enrollmentAuthVersion;

    @Column(name = "last_used_step", nullable = false)
    private long lastUsedStep = -1;

    protected MfaCredential() {}

    public static MfaCredential forUser(UUID userId) {
        var credential = new MfaCredential();
        credential.userId = userId;
        return credential;
    }

    public void beginEnrollment(String ciphertext, Instant expiresAt, long authVersion) {
        pendingSecretCiphertext = ciphertext;
        enrollmentExpiresAt = expiresAt;
        enrollmentAuthVersion = authVersion;
    }

    public void confirm(long acceptedStep) {
        secretCiphertext = pendingSecretCiphertext;
        pendingSecretCiphertext = null;
        enrollmentExpiresAt = null;
        enrollmentAuthVersion = null;
        lastUsedStep = acceptedStep;
    }

    public void consumeStep(long step) { lastUsedStep = step; }

    public UUID getUserId() { return userId; }
    public boolean isEnabled() { return secretCiphertext != null; }
    public String getSecretCiphertext() { return secretCiphertext; }
    public String getPendingSecretCiphertext() { return pendingSecretCiphertext; }
    public Instant getEnrollmentExpiresAt() { return enrollmentExpiresAt; }
    public Long getEnrollmentAuthVersion() { return enrollmentAuthVersion; }
    public long getLastUsedStep() { return lastUsedStep; }
}
