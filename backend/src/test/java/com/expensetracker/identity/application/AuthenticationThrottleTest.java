package com.expensetracker.identity.application;

import com.expensetracker.shared.error.DomainException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthenticationThrottleTest {
    @Test
    void invalidPoliciesAndUnboundedKeysDoNotReachDatabase() {
        var database = mock(JdbcTemplate.class);
        var throttle = new AuthenticationThrottle(database);
        for (var maximum : new int[] {0, -1, 10001, Integer.MAX_VALUE}) {
            assertThrows(DomainException.class, () -> throttle.consume("synthetic", maximum, Duration.ofMinutes(1)));
        }
        for (var window : new Duration[] {Duration.ZERO, Duration.ofMillis(999), Duration.ofDays(2), null}) {
            assertThrows(DomainException.class, () -> throttle.consume("synthetic", 1, window));
        }
        for (var bucket : new String[] {null, "", " ", "s".repeat(4097)}) {
            assertThrows(DomainException.class, () -> throttle.consume(bucket, 1, Duration.ofMinutes(1)));
        }
        verifyNoInteractions(database);
    }
}
