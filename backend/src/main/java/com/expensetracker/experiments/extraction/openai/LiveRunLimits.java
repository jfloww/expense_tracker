package com.expensetracker.experiments.extraction.openai;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Explicit limits for one opted-in synthetic run. Reservations enforce the caller's declared
 * per-request cost envelope; they cannot guarantee a provider invoice without a verified billing
 * bound. Failed, timed-out, and retried requests retain their reservations. Actual cost stays unknown.
 */
public record LiveRunLimits(String model, boolean allowLive, int maxOutputTokens, long maxFileBytes,
                            int maxRows, int timeoutSeconds, int maxRequests, int deadlineSeconds,
                            BigDecimal maxCostUsd, BigDecimal requestCostCeilingUsd, String pricingBasis) {
    public static final int MAX_OUTPUT_TOKENS = 4_096;
    public static final long MAX_FILE_BYTES = 1_000_000;
    public static final int MAX_ROWS = 100;
    public static final int MAX_TIMEOUT_SECONDS = 120;
    public static final int MAX_REQUESTS = 4;
    public static final int MAX_DEADLINE_SECONDS = 240;

    public LiveRunLimits {
        if (!allowLive) throw new IllegalArgumentException("Live execution requires explicit --allow-live consent");
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) {
            throw new IllegalArgumentException("An explicit bounded model ID is required");
        }
        if (maxOutputTokens < 64 || maxOutputTokens > MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("Output token limit must be between 64 and 4096");
        }
        if (maxFileBytes < 1 || maxFileBytes > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("PDF byte limit must be between 1 and 1000000");
        }
        bounded(maxRows, MAX_ROWS, "max rows");
        bounded(timeoutSeconds, MAX_TIMEOUT_SECONDS, "request timeout seconds");
        bounded(maxRequests, MAX_REQUESTS, "max requests");
        bounded(deadlineSeconds, MAX_DEADLINE_SECONDS, "run deadline seconds");
        if (timeoutSeconds > deadlineSeconds) {
            throw new IllegalArgumentException("Request timeout cannot exceed the total run deadline");
        }
        maxCostUsd = positiveUsd(maxCostUsd);
        requestCostCeilingUsd = positiveUsd(requestCostCeilingUsd);
        if (requestCostCeilingUsd.compareTo(maxCostUsd) > 0) {
            throw new IllegalArgumentException("Per-request reservation exceeds the approved run budget");
        }
        if (pricingBasis == null || pricingBasis.isBlank() || pricingBasis.length() > 500
                || pricingBasis.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A bounded one-line pricing/reservation basis is required");
        }
    }

    /** Parsing occurs before side effects; cost arguments must be actual decimal USD values. */
    public static BigDecimal parseUsd(String input) {
        if (input == null || input.length() > 19 || !input.matches("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,6})?")) {
            throw new IllegalArgumentException("USD costs require a positive canonical decimal with at most six fractional digits");
        }
        return positiveUsd(new BigDecimal(input));
    }

    /** Never include the key in exceptions, diagnostics, records, or artifacts. No prefix is assumed. */
    public static String requireApiKey(String key) {
        if (key == null || key.length() < 8 || key.length() > 512
                || key.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw new IllegalArgumentException("An explicit valid OPENAI_API_KEY is required for live execution");
        }
        return key;
    }

    public Budget newBudget(Clock clock) { return new Budget(this, clock, System::nanoTime); }

    Budget newBudget(Clock clock, LongSupplier nanoTime) { return new Budget(this, clock, nanoTime); }

    private static BigDecimal positiveUsd(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() < 0 || value.scale() > 6 || value.precision() > 12) {
            throw new IllegalArgumentException("USD limits must be positive, bounded decimals with at most six fractional digits");
        }
        BigDecimal canonical = value.stripTrailingZeros();
        return canonical.scale() < 0 ? canonical.setScale(0) : canonical;
    }

    private static void bounded(int value, int max, String name) {
        if (value < 1 || value > max) throw new IllegalArgumentException("Invalid " + name + " limit");
    }

    /** A single-run reservation state shared by every adapter attempt, including retries. */
    public static final class Budget {
        private final LiveRunLimits limits;
        private final Clock clock;
        private final LongSupplier nanoTime;
        private final long startedNanos;
        private final Instant deadlineAt;
        private int attemptsReserved;
        private BigDecimal totalReservedUsd = BigDecimal.ZERO;

        private Budget(LiveRunLimits limits, Clock clock, LongSupplier nanoTime) {
            this.limits = limits;
            this.clock = Objects.requireNonNull(clock, "Clock is required");
            this.nanoTime = Objects.requireNonNull(nanoTime, "Monotonic clock is required");
            startedNanos = nanoTime.getAsLong();
            deadlineAt = clock.instant().plusSeconds(limits.deadlineSeconds());
        }

        /** Reserve immediately before sending, not after receiving usage; denied attempts consume nothing. */
        public synchronized Reservation reserveRequest() {
            requireTimeRemaining();
            if (attemptsReserved >= limits.maxRequests()) throw exceeded("live_request_limit_exceeded");
            BigDecimal after = totalReservedUsd.add(limits.requestCostCeilingUsd());
            if (after.compareTo(limits.maxCostUsd()) > 0) throw exceeded("live_cost_reservation_exceeded");
            attemptsReserved = Math.incrementExact(attemptsReserved);
            totalReservedUsd = after;
            return new Reservation(attemptsReserved, limits.requestCostCeilingUsd(), totalReservedUsd,
                    limits.maxCostUsd().subtract(totalReservedUsd));
        }

        /** Method-reference compatible with the adapter's before-send AttemptBudget callback. */
        public void reserveAttempt() { reserveRequest(); }

        public synchronized int attemptsReserved() { return attemptsReserved; }
        public synchronized BigDecimal totalReservedUsd() { return totalReservedUsd; }
        public synchronized BigDecimal remainingBudgetUsd() { return limits.maxCostUsd().subtract(totalReservedUsd); }
        public Instant deadlineAt() { return deadlineAt; }

        /** Callers cap every HTTP timeout to this duration as well as the per-request timeout. */
        public Duration remainingTime() {
            Duration wallRemaining = Duration.between(clock.instant(), deadlineAt);
            long elapsed = nanoTime.getAsLong() - startedNanos;
            Duration monotonicRemaining = Duration.ofSeconds(limits.deadlineSeconds())
                    .minusNanos(Math.max(0, elapsed));
            Duration remaining = wallRemaining.compareTo(monotonicRemaining) < 0 ? wallRemaining : monotonicRemaining;
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }

        public Duration nextRequestTimeout() {
            Duration remaining = remainingTime();
            if (remaining.isZero()) throw exceeded("live_deadline_exceeded");
            Duration perRequest = Duration.ofSeconds(limits.timeoutSeconds());
            return remaining.compareTo(perRequest) < 0 ? remaining : perRequest;
        }

        private void requireTimeRemaining() {
            if (remainingTime().isZero()) throw exceeded("live_deadline_exceeded");
        }
    }

    public record Reservation(int requestNumber, BigDecimal reservedCostUsd,
                               BigDecimal totalReservedUsd, BigDecimal remainingBudgetUsd) {}

    /** Bounded codes can be traced without exposing request content or credentials. */
    public static final class LimitExceededException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private LimitExceededException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    private static LimitExceededException exceeded(String code) { return new LimitExceededException(code); }
}
