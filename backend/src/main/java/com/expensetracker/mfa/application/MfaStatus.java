package com.expensetracker.mfa.application;

import java.time.Instant;

public record MfaStatus(boolean enabled, int recoveryCodesRemaining,
                        boolean enrollmentPending, Instant enrollmentExpiresAt) {}
