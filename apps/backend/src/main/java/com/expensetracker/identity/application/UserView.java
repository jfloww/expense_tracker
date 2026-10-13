package com.expensetracker.identity.application;

import java.time.Instant;
import java.util.UUID;

/** An identity result deliberately excluding password credentials. */
public record UserView(UUID id, String email, String displayName, Instant createdAt) {}
