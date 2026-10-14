package com.expensetracker.households.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateHouseholdRequest(@NotBlank @Size(max = 120) String name) {}
