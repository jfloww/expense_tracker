package com.expensetracker.households.application;

import com.expensetracker.households.domain.Role;
import java.time.Instant;
import java.util.UUID;

public record HouseholdView(UUID id, String name, Role role, Instant createdAt) {}
