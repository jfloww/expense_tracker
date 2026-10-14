package com.expensetracker.identity.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** Stored in the server session; contains no password hash or MFA material. */
public record SessionPrincipal(UUID userId, String email, long authVersion)
        implements Principal, Serializable {
    @Override public String getName() { return userId.toString(); }
}
