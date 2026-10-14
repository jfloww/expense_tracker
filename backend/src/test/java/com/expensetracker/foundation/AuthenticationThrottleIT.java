package com.expensetracker.foundation;

import com.expensetracker.identity.application.AuthenticationThrottle;
import com.expensetracker.shared.error.DomainException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationThrottleIT extends FoundationTestSupport {
    @Autowired private AuthenticationThrottle throttle;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void limitsAreDurableAndBucketContainsOnlyDigest() {
        var bucket = "login:account:synthetic@example.test";
        throttle.consume(bucket, 2, Duration.ofMinutes(1));
        throttle.consume(bucket, 2, Duration.ofMinutes(1));
        var error = assertThrows(DomainException.class,
                () -> throttle.consume(bucket, 2, Duration.ofMinutes(1)));
        assertEquals(DomainException.Kind.RATE_LIMITED, error.kind());
        assertEquals("too_many_attempts", error.code());
        assertTrue(error.retryAfterSeconds() >= 1 && error.retryAfterSeconds() <= 60);
        assertEquals(3, jdbc.queryForObject("SELECT attempts FROM auth_attempt_limits", Integer.class));
        var stored = jdbc.queryForObject("SELECT bucket_hash FROM auth_attempt_limits", String.class);
        assertNotNull(stored);
        assertTrue(stored.matches("[0-9a-f]{64}"));
        assertFalse(stored.contains("synthetic"));
    }

    @Test
    void outerAuthenticationFailureCannotRollBackAttemptConsumption() {
        var outer = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> outer.executeWithoutResult(status -> {
            throttle.consume("login:network:synthetic-network", 1, Duration.ofMinutes(1));
            throw new IllegalStateException("Synthetic authentication failure");
        }));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM auth_attempt_limits", Integer.class));
        assertThrows(DomainException.class,
                () -> throttle.consume("login:network:synthetic-network", 1, Duration.ofMinutes(1)));
    }

    @Test
    void concurrentConsumptionAdmitsOnlyConfiguredMaximum() throws Exception {
        var workers = 12;
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (var index = 0; index < workers; index++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        throttle.consume("mfa:synthetic-race", 4, Duration.ofMinutes(1));
                        return true;
                    } catch (DomainException error) {
                        assertEquals(DomainException.Kind.RATE_LIMITED, error.kind());
                        return false;
                    }
                }));
            }
            start.countDown();
            var admitted = 0;
            for (var result : results) {
                if (result.get(20, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertEquals(4, admitted);
            assertEquals(5, jdbc.queryForObject("SELECT attempts FROM auth_attempt_limits", Integer.class));
        }
    }

    @Test
    void expiredWindowResetsAndPurposeBucketsAreIndependent() {
        throttle.consume("login:synthetic", 1, Duration.ofMinutes(1));
        assertThrows(DomainException.class, () -> throttle.consume("login:synthetic", 1, Duration.ofMinutes(1)));
        throttle.consume("mfa:synthetic", 1, Duration.ofMinutes(1));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM auth_attempt_limits", Integer.class));
        jdbc.update("UPDATE auth_attempt_limits SET window_start = now() - interval '2 minutes', "
                + "expires_at = now() - interval '1 minute'");
        throttle.consume("login:synthetic", 1, Duration.ofMinutes(1));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM auth_attempt_limits", Integer.class));
    }

    @Test
    void deniedAttemptsDoNotExtendTheOriginalWindowOrOverflowTheCounter() {
        throttle.consume("login:synthetic-bound", 1, Duration.ofMinutes(1));
        var expiry = jdbc.queryForObject("SELECT expires_at FROM auth_attempt_limits", java.sql.Timestamp.class);
        for (var attempt = 0; attempt < 8; attempt++) {
            assertThrows(DomainException.class,
                    () -> throttle.consume("login:synthetic-bound", 1, Duration.ofMinutes(1)));
        }
        assertEquals(expiry, jdbc.queryForObject("SELECT expires_at FROM auth_attempt_limits", java.sql.Timestamp.class));
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM auth_attempt_limits", Integer.class));
    }

    @Test
    void expiredCleanupIsBoundedAndLeavesLiveBuckets() {
        throttle.consume("live:synthetic", 1, Duration.ofMinutes(5));
        jdbc.update("""
                INSERT INTO auth_attempt_limits(bucket_hash, window_start, expires_at, attempts)
                SELECT md5(index::text) || md5(index::text), now() - interval '2 minutes',
                    now() - interval '1 minute', 1 FROM generate_series(1, 205) AS index
                """);
        assertEquals(100, throttle.purgeExpired());
        assertEquals(106, jdbc.queryForObject("SELECT count(*) FROM auth_attempt_limits", Integer.class));
        assertEquals(100, throttle.purgeExpired());
        assertEquals(5, throttle.purgeExpired());
        assertEquals(0, throttle.purgeExpired());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_attempt_limits", Integer.class));
    }
}
