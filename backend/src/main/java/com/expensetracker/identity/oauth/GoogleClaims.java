package com.expensetracker.identity.oauth;

/** Only claims from Spring's verified OIDC ID token may enter this application contract. */
public record GoogleClaims(String issuer, String subject, String email,
                           boolean emailVerified, String displayName) {}
