package com.expensetracker.experiments.extraction.openai;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveRunLimitsTest {
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    @Test
    void reservesExactDeclaredCeilingsBeforeRequestsAndRetainsThemAfterFailures() {
        var budget = limits("0.03", "0.01", 4).newBudget(Clock.fixed(NOW, ZoneOffset.UTC));
        var first = budget.reserveRequest();
        assertEquals(1, first.requestNumber());
        assertEquals(new BigDecimal("0.01"), first.reservedCostUsd());
        assertEquals(new BigDecimal("0.02"), first.remainingBudgetUsd());
        // A failed send has no refund path; a retry must make another full reservation.
        budget.reserveAttempt();
        budget.reserveAttempt();
        var rejected = assertThrows(LiveRunLimits.LimitExceededException.class, budget::reserveRequest);
        assertEquals("live_cost_reservation_exceeded", rejected.code());
        assertEquals(3, budget.attemptsReserved());
        assertEquals(new BigDecimal("0.03"), budget.totalReservedUsd());
        assertEquals(0, budget.remainingBudgetUsd().signum());
    }

    @Test
    void requestCountAlsoStopsRunsWithAmpleDeclaredBudget() {
        var budget = limits("1", "0.01", 2).newBudget(Clock.fixed(NOW, ZoneOffset.UTC));
        budget.reserveAttempt();
        budget.reserveAttempt();
        assertEquals("live_request_limit_exceeded",
                assertThrows(LiveRunLimits.LimitExceededException.class, budget::reserveAttempt).code());
        assertEquals(2, budget.attemptsReserved());
    }

    @Test
    void aPartialRemainingBudgetCannotAuthorizeARequestEvenIfActualCostMayBeLower() {
        var budget = limits("0.025", "0.01", 4).newBudget(Clock.fixed(NOW, ZoneOffset.UTC));
        budget.reserveAttempt();
        budget.reserveAttempt();
        assertEquals(new BigDecimal("0.005"), budget.remainingBudgetUsd());
        assertEquals("live_cost_reservation_exceeded",
                assertThrows(LiveRunLimits.LimitExceededException.class, budget::reserveAttempt).code());
        assertEquals(2, budget.attemptsReserved());
        assertEquals(new BigDecimal("0.02"), budget.totalReservedUsd());
    }

    @Test
    void concurrentAttemptsCannotOverbookTheSameCostReservation() throws Exception {
        var budget = limits("0.01", "0.01", 4).newBudget(Clock.fixed(NOW, ZoneOffset.UTC));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> reserveAfter(start, budget));
            var second = workers.submit(() -> reserveAfter(start, budget));
            start.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, budget.attemptsReserved());
        assertEquals(new BigDecimal("0.01"), budget.totalReservedUsd());
    }

    @Test
    void totalDeadlineStopsNewRequestsAndCapsTheNextHttpTimeout() {
        var clock = new MutableClock();
        var limits = new LiveRunLimits("explicit-model", true, 4096, 1_000_000, 100,
                120, 4, 240, usd("1"), usd("0.01"), "Caller conservative reservation, invoice unknown");
        var budget = limits.newBudget(clock, () -> 0L);
        assertEquals(Duration.ofSeconds(120), budget.nextRequestTimeout());
        clock.set(NOW.plusSeconds(230));
        assertEquals(Duration.ofSeconds(10), budget.nextRequestTimeout());
        budget.reserveAttempt();
        clock.set(NOW.plusSeconds(240));
        assertEquals(Duration.ZERO, budget.remainingTime());
        assertEquals("live_deadline_exceeded",
                assertThrows(LiveRunLimits.LimitExceededException.class, budget::reserveAttempt).code());
        assertThrows(LiveRunLimits.LimitExceededException.class, budget::nextRequestTimeout);
        clock.set(NOW.plusSeconds(241));
        assertEquals(Duration.ZERO, budget.remainingTime());
        assertEquals(1, budget.attemptsReserved());
    }

    @Test
    void backwardWallClockCannotExtendTheMonotonicRunDeadline() {
        var clock = new MutableClock();
        var nanos = new AtomicLong(Long.MAX_VALUE - Duration.ofSeconds(100).toNanos());
        var budget = limits("1", "0.01", 4).newBudget(clock, nanos::get);
        // nanoTime subtraction remains valid across wraparound for this bounded interval.
        nanos.addAndGet(Duration.ofSeconds(230).toNanos());
        clock.set(NOW.minusSeconds(3_600));
        assertEquals(Duration.ofSeconds(10), budget.remainingTime());
        nanos.addAndGet(Duration.ofSeconds(10).toNanos());
        assertEquals(Duration.ZERO, budget.remainingTime());
        assertEquals("live_deadline_exceeded",
                assertThrows(LiveRunLimits.LimitExceededException.class, budget::reserveAttempt).code());
        assertEquals(0, budget.attemptsReserved());
    }

    @Test
    void explicitConsentModelAndAllResourceCapsAreRequiredBeforeSideEffects() {
        assertThrows(IllegalArgumentException.class, () -> configured("explicit-model", false, 4096, 1_000_000, 100, 120, 2, 240));
        for (String model : List.of("", " ", "model\nsecret", "x".repeat(101))) {
            assertThrows(IllegalArgumentException.class, () -> configured(model, true, 4096, 1_000_000, 100, 120, 2, 240));
        }
        assertThrows(IllegalArgumentException.class, () -> configured("model", true, 4097, 1_000_000, 100, 120, 2, 240));
        assertThrows(IllegalArgumentException.class, () -> configured("model", true, 63, 1_000_000, 100, 120, 2, 240));
        assertDoesNotThrow(() -> configured("x".repeat(100), true, 64, 1_000_000, 100, 120, 2, 240));
        assertThrows(IllegalArgumentException.class, () -> configured("model", true, 4096, 1_000_001, 100, 120, 2, 240));
        assertThrows(IllegalArgumentException.class, () -> configured("model", true, 4096, 1_000_000, 101, 120, 2, 240));
        assertThrows(IllegalArgumentException.class, () -> configured("model", true, 4096, 1_000_000, 100, 121, 2, 240));
        assertThrows(IllegalArgumentException.class, () -> configured("model", true, 4096, 1_000_000, 100, 120, 5, 240));
        assertThrows(IllegalArgumentException.class, () -> configured("model", true, 4096, 1_000_000, 100, 120, 2, 241));
        assertThrows(IllegalArgumentException.class, () -> configured("model", true, 4096, 1_000_000, 100, 120, 2, 100));
        assertThrows(IllegalArgumentException.class, () -> configured("model", true, 0, 1_000_000, 100, 120, 2, 240));
        assertThrows(IllegalArgumentException.class, () -> configured("model", true, 4096, 0, 100, 120, 2, 240));
    }

    @Test
    void costsArePositiveCanonicalBoundedDecimalsAndRequireAnHonestBasis() {
        for (String invalid : List.of("0", "0.000000", "-1", "+1", "01", " 1", "1 ", "1e-2", "1.0000001", "NaN")) {
            assertThrows(IllegalArgumentException.class, () -> LiveRunLimits.parseUsd(invalid), invalid);
        }
        assertEquals(new BigDecimal("0.01"), LiveRunLimits.parseUsd("0.010000"));
        assertThrows(IllegalArgumentException.class, () -> limits("0.01", "0.02", 2));
        assertThrows(IllegalArgumentException.class, () -> new LiveRunLimits("model", true, 4096, 100, 100,
                120, 2, 240, usd("1"), usd("0.01"), ""));
        assertThrows(IllegalArgumentException.class, () -> new LiveRunLimits("model", true, 4096, 100, 100,
                120, 2, 240, usd("1"), usd("0.01"), "unbounded\nraw details"));
    }

    @Test
    void missingOrHeaderUnsafeCredentialsAreRejectedWithoutEchoingTheKey() {
        for (String key : List.of("", " ", "x".repeat(7), "x".repeat(513), "synthetic-secret\r\nX-Injected: true", "synthetic secret", "\u2603")) {
            var error = assertThrows(IllegalArgumentException.class, () -> LiveRunLimits.requireApiKey(key));
            assertFalse(error.getMessage().contains("synthetic-secret"));
        }
        assertThrows(IllegalArgumentException.class, () -> LiveRunLimits.requireApiKey(null));
        assertEquals("synthetic-test-key", LiveRunLimits.requireApiKey("synthetic-test-key"));
        assertEquals("x".repeat(8), LiveRunLimits.requireApiKey("x".repeat(8)));
        assertEquals("x".repeat(512), LiveRunLimits.requireApiKey("x".repeat(512)));
        assertFalse(limits("1", "0.01", 2).toString().contains("synthetic-test-key"));
    }

    private static int reserveAfter(CountDownLatch start, LiveRunLimits.Budget budget) throws InterruptedException {
        start.await();
        try { budget.reserveAttempt(); return 1; }
        catch (LiveRunLimits.LimitExceededException rejected) {
            assertEquals("live_cost_reservation_exceeded", rejected.code());
            return 0;
        }
    }

    private static LiveRunLimits limits(String budget, String ceiling, int maxRequests) {
        return new LiveRunLimits("explicit-model", true, 4096, 1_000_000, 100, 120, maxRequests, 240,
                usd(budget), usd(ceiling), "Caller conservative ceiling covering PDF/text input and output; invoice unknown");
    }

    private static LiveRunLimits configured(String model, boolean live, int tokens, long bytes,
            int rows, int timeout, int requests, int deadline) {
        return new LiveRunLimits(model, live, tokens, bytes, rows, timeout, requests, deadline,
                usd("1"), usd("0.01"), "Caller reservation estimate, not guaranteed provider charges");
    }

    private static BigDecimal usd(String value) { return new BigDecimal(value); }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
