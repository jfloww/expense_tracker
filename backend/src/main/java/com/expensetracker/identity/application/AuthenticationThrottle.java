package com.expensetracker.identity.application;

import com.expensetracker.shared.error.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable per-purpose account/network buckets; authentication failures never undo consumption. */
@Service
public class AuthenticationThrottle {
    private static final int CLEANUP_BATCH = 100;
    private static final Duration MAX_WINDOW = Duration.ofDays(1);
    private final JdbcTemplate database;

    public AuthenticationThrottle(JdbcTemplate database) {
        this.database = database;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = DomainException.class)
    public void consume(String bucket, int max, Duration window) {
        if (bucket == null || bucket.isBlank() || bucket.length() > 4096 || max < 1 || max > 10000
                || window == null || window.compareTo(Duration.ofSeconds(1)) < 0
                || window.compareTo(MAX_WINDOW) > 0) {
            throw DomainException.invalid("invalid_authentication_limit");
        }
        purgeExpired();
        var result = database.queryForObject("""
                WITH moment AS (SELECT clock_timestamp() AS now)
                INSERT INTO auth_attempt_limits (bucket_hash, window_start, expires_at, attempts)
                SELECT ?, now, now + (? * interval '1 millisecond'), 1 FROM moment
                ON CONFLICT (bucket_hash) DO UPDATE SET
                    window_start = CASE WHEN auth_attempt_limits.expires_at <= EXCLUDED.window_start
                        THEN EXCLUDED.window_start ELSE auth_attempt_limits.window_start END,
                    expires_at = CASE WHEN auth_attempt_limits.expires_at <= EXCLUDED.window_start
                        THEN EXCLUDED.expires_at ELSE auth_attempt_limits.expires_at END,
                    attempts = CASE WHEN auth_attempt_limits.expires_at <= EXCLUDED.window_start
                        THEN 1 ELSE LEAST(auth_attempt_limits.attempts + 1, ?) END
                RETURNING attempts,
                    GREATEST(1, CEIL(EXTRACT(EPOCH FROM
                        (expires_at - (SELECT now FROM moment)))))::integer AS retry_after
                """, (row, index) -> new Attempt(row.getInt("attempts"), row.getInt("retry_after")),
                digest(bucket), window.toMillis(), max + 1);
        if (result == null) {
            throw new IllegalStateException("Authentication limit did not return its result");
        }
        if (result.attempts() > max) {
            throw DomainException.tooManyRequests(result.retryAfterSeconds());
        }
    }

    /** Bounded database work and SKIP LOCKED allow several application instances to clean safely. */
    @Scheduled(fixedDelayString = "${app.authentication-throttle.cleanup-delay:PT1M}",
            initialDelayString = "${app.authentication-throttle.cleanup-delay:PT1M}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired() {
        return database.update("""
                WITH expired AS (
                    SELECT bucket_hash FROM auth_attempt_limits
                    WHERE expires_at <= clock_timestamp()
                    ORDER BY expires_at, bucket_hash LIMIT ? FOR UPDATE SKIP LOCKED
                )
                DELETE FROM auth_attempt_limits AS limits USING expired
                WHERE limits.bucket_hash = expired.bucket_hash
                """, CLEANUP_BATCH);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private record Attempt(int attempts, int retryAfterSeconds) {}
}
