package com.expensetracker.identity.application;

import com.expensetracker.shared.error.DomainException;
import java.util.UUID;

/** Identity supplied by an authenticated boundary, never directly by a request body. */
public record Actor(UUID userId) {
    public Actor {
        if (userId == null) {
            throw DomainException.invalid("invalid_actor");
        }
    }
}
