package com.expensetracker.mfa.application;

import java.time.Instant;

/** Returned only once over an authenticated, private response; never log this value. */
public record Enrollment(String secret, String otpauthUri, Instant expiresAt) {
    @Override
    public String toString() {
        return "Enrollment[secret=<redacted>, otpauthUri=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
