package com.expensetracker.foundation;

import com.expensetracker.identity.application.Actor;
import com.expensetracker.mfa.application.Enrollment;
import com.expensetracker.mfa.application.MfaService;
import com.expensetracker.mfa.domain.Totp;
import com.expensetracker.mfa.persistence.MfaCredentialRepository;
import com.expensetracker.mfa.security.MfaSecretCipher;
import com.expensetracker.shared.error.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(MfaServicesIT.TestClockConfiguration.class)
class MfaServicesIT extends FoundationTestSupport {
    private static final String PASSWORD = "Synthetic-password-2026";
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    @Autowired private MfaService mfa;
    @Autowired private MfaSecretCipher cipher;
    @Autowired private MutableClock testClock;
    @Autowired private MfaCredentialRepository credentialRepository;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void resetClock() { testClock.set(NOW); }

    @Test
    void enrollmentRequiresPasswordExpiresAndRestartsWithoutActivatingEarly() {
        Actor user = actor();
        assertFalse(mfa.status(user).enabled());
        assertCode("invalid_credentials", () -> mfa.beginEnrollment(user, "wrong-password"));
        Enrollment enrollment = mfa.beginEnrollment(user, PASSWORD);
        assertEquals(NOW.plusSeconds(600), enrollment.expiresAt());
        assertTrue(enrollment.otpauthUri().contains("algorithm=SHA1&digits=6&period=30"));
        assertTrue(mfa.status(user).enrollmentPending());
        assertFalse(mfa.isEnabled(user.userId()));
        testClock.set(NOW.plusSeconds(600));
        assertCode("mfa_enrollment_expired", () -> mfa.confirmEnrollment(user, Totp.codeAt(enrollment.secret(), testClock.instant())));
        assertFalse(mfa.status(user).enrollmentPending());
        Enrollment restarted = mfa.beginEnrollment(user, PASSWORD);
        assertNotEquals(enrollment.secret(), restarted.secret());
        assertEquals(10, mfa.confirmEnrollment(user, Totp.codeAt(restarted.secret(), testClock.instant())).size());
        assertTrue(mfa.status(user).enabled());
    }

    @Test
    void secretsAreEncryptedAtRestBoundToUserAndConfirmationRevokesPriorVersions() {
        Actor user = actor();
        Enrollment enrollment = mfa.beginEnrollment(user, PASSWORD);
        String pendingCiphertext = jdbc.queryForObject(
                "SELECT pending_secret_ciphertext FROM mfa_credentials WHERE user_id = ?", String.class, user.userId());
        assertNotEquals(enrollment.secret(), pendingCiphertext);
        assertEquals(enrollment.secret(), cipher.decrypt(user.userId(), pendingCiphertext));
        Actor other = actor();
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(other.userId(), pendingCiphertext));
        List<String> codes = mfa.confirmEnrollment(user, Totp.codeAt(enrollment.secret(), NOW));
        assertEquals(10, new HashSet<>(codes).size());
        assertEquals(1, authVersion(user));
        assertEquals(pendingCiphertext, jdbc.queryForObject(
                "SELECT secret_ciphertext FROM mfa_credentials WHERE user_id = ?", String.class, user.userId()));
        var hashes = jdbc.queryForList("SELECT code_hash FROM mfa_recovery_codes WHERE user_id = ?", String.class, user.userId());
        assertEquals(10, hashes.size());
        assertTrue(hashes.stream().allMatch(hash -> hash.matches("[a-f0-9]{64}") && !codes.contains(hash)));
        assertCode("invalid_credentials", () -> mfa.verifyLogin(user.userId(), 0, codes.getFirst()));
        mfa.verifyLogin(user.userId(), 1, codes.getFirst());
        assertEquals(9, mfa.status(user).recoveryCodesRemaining());
        assertCode("mfa_already_enabled", () -> mfa.beginEnrollment(user, PASSWORD));
    }

    @Test
    void setupAndSuccessfulLoginTotpCodesCannotBeReplayed() {
        Actor user = actor();
        Enrollment enrollment = mfa.beginEnrollment(user, PASSWORD);
        String setupCode = Totp.codeAt(enrollment.secret(), NOW);
        mfa.confirmEnrollment(user, setupCode);
        assertCode("invalid_mfa_code", () -> mfa.verifyLogin(user.userId(), 1, setupCode));
        testClock.set(NOW.plusSeconds(120));
        String nextCode = Totp.codeAt(enrollment.secret(), testClock.instant());
        mfa.verifyLogin(user.userId(), 1, nextCode);
        assertCode("invalid_mfa_code", () -> mfa.verifyLogin(user.userId(), 1, nextCode));
        assertEquals(10, mfa.status(user).recoveryCodesRemaining());
    }

    @Test
    void recoveryCodesAreSingleUseOwnedAndCanBeExhausted() {
        Actor user = actor();
        List<String> codes = enroll(user);
        Actor other = actor();
        enroll(other);
        assertCode("invalid_mfa_code", () -> mfa.verifyLogin(other.userId(), 1, codes.getFirst()));
        for (String code : codes) {
            mfa.verifyLogin(user.userId(), 1, code);
            assertCode("invalid_mfa_code", () -> mfa.verifyLogin(user.userId(), 1, code));
        }
        assertEquals(0, mfa.status(user).recoveryCodesRemaining());
        assertCode("invalid_mfa_code", () -> mfa.verifyLogin(user.userId(), 1, "0".repeat(32)));
    }

    @Test
    void securityChangesRequirePasswordAndFactorAndReplaceOldCodesAtomically() {
        Actor user = actor();
        List<String> original = enroll(user);
        assertCode("invalid_credentials", () -> mfa.regenerateRecoveryCodes(user, "wrong", original.getFirst()));
        assertEquals(10, mfa.status(user).recoveryCodesRemaining());
        List<String> replacement = mfa.regenerateRecoveryCodes(user, PASSWORD, original.getFirst());
        assertEquals(2, authVersion(user));
        assertEquals(10, mfa.status(user).recoveryCodesRemaining());
        assertTrue(replacement.stream().noneMatch(original::contains));
        assertCode("invalid_mfa_code", () -> mfa.verifyLogin(user.userId(), 2, original.get(1)));
        assertCode("invalid_credentials", () -> mfa.disable(user, "wrong", replacement.getFirst()));
        assertTrue(mfa.isEnabled(user.userId()));
        mfa.disable(user, PASSWORD, replacement.getFirst());
        assertFalse(mfa.status(user).enabled());
        assertEquals(3, authVersion(user));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id = ?", Integer.class, user.userId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM mfa_credentials WHERE user_id = ?", Integer.class, user.userId()));
    }

    @Test
    void changedIdentityVersionInvalidatesPendingEnrollmentAndDisabledUsersAreDenied() {
        Actor user = actor();
        Enrollment enrollment = mfa.beginEnrollment(user, PASSWORD);
        jdbc.update("UPDATE app_users SET auth_version = auth_version + 1 WHERE id = ?", user.userId());
        assertCode("mfa_enrollment_required", () -> mfa.confirmEnrollment(user, Totp.codeAt(enrollment.secret(), NOW)));
        assertFalse(mfa.status(user).enrollmentPending());
        mfa.beginEnrollment(user, PASSWORD);
        jdbc.update("UPDATE app_users SET enabled = false WHERE id = ?", user.userId());
        assertCode("invalid_credentials", () -> mfa.status(user));
        assertCode("invalid_credentials", () -> mfa.beginEnrollment(user, PASSWORD));
    }

    @Test
    void versionAwareLifecycleOperationsRejectStaleSessionsBeforeChangingFactors() {
        Actor user = actor();
        Enrollment pending = mfa.beginEnrollment(user, 0, PASSWORD);
        jdbc.update("UPDATE app_users SET auth_version = auth_version + 1 WHERE id = ?", user.userId());
        assertCode("invalid_credentials", () -> mfa.beginEnrollment(user, 0, PASSWORD));
        assertCode("invalid_credentials", () -> mfa.confirmEnrollment(user, 0, Totp.codeAt(pending.secret(), NOW)));
        Enrollment current = mfa.beginEnrollment(user, 1, PASSWORD);
        List<String> codes = mfa.confirmEnrollment(user, 1, Totp.codeAt(current.secret(), NOW));
        assertCode("invalid_credentials", () -> mfa.disable(user, 1, PASSWORD, codes.getFirst()));
        assertCode("invalid_credentials", () -> mfa.regenerateRecoveryCodes(user, 1, PASSWORD, codes.getFirst()));
        assertEquals(10, mfa.status(user).recoveryCodesRemaining());
        assertEquals(2, authVersion(user));
        List<String> fresh = mfa.regenerateRecoveryCodes(user, 2, PASSWORD, codes.getFirst());
        assertEquals(10, fresh.size());
        assertEquals(3, authVersion(user));
    }

    @Test
    void concurrentRecoveryUseHasExactlyOneWinner() throws Exception {
        Actor user = actor();
        List<String> codes = enroll(user);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tryLoginAfter(start, user, codes.getFirst()));
            var second = executor.submit(() -> tryLoginAfter(start, user, codes.getFirst()));
            start.countDown();
            int successes = (first.get(15, TimeUnit.SECONDS) ? 1 : 0) + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        }
        assertEquals(9, mfa.status(user).recoveryCodesRemaining());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM mfa_recovery_codes WHERE user_id = ? AND used_at IS NOT NULL", Integer.class, user.userId()));
    }

    @Test
    void concurrentTotpUseHasExactlyOneWinner() throws Exception {
        Actor user = actor();
        Enrollment enrollment = mfa.beginEnrollment(user, PASSWORD);
        mfa.confirmEnrollment(user, Totp.codeAt(enrollment.secret(), NOW));
        testClock.set(NOW.plusSeconds(120));
        String code = Totp.codeAt(enrollment.secret(), testClock.instant());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tryLoginAfter(start, user, code));
            var second = executor.submit(() -> tryLoginAfter(start, user, code));
            start.countDown();
            int successes = (first.get(15, TimeUnit.SECONDS) ? 1 : 0) + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        }
        assertEquals(10, mfa.status(user).recoveryCodesRemaining());
    }

    @Test
    void futureWindowAcceptanceRejectsThatStepAndEarlierStepsAfterClockAdvances() {
        Actor user = actor();
        enroll(user);
        // Use the fixed RFC synthetic key to make adjacent-window behavior reproducible.
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        jdbc.update("UPDATE mfa_credentials SET secret_ciphertext = ? WHERE user_id = ?",
                cipher.encrypt(user.userId(), secret), user.userId());
        testClock.set(NOW.plusSeconds(120));
        String current = Totp.codeAt(secret, testClock.instant());
        String future = Totp.codeAt(secret, testClock.instant().plusSeconds(30));
        mfa.verifyLogin(user.userId(), 1, future);
        assertCode("invalid_mfa_code", () -> mfa.verifyLogin(user.userId(), 1, current));
        testClock.set(NOW.plusSeconds(150));
        assertCode("invalid_mfa_code", () -> mfa.verifyLogin(user.userId(), 1, future));
        testClock.set(NOW.plusSeconds(180));
        mfa.verifyLogin(user.userId(), 1, Totp.codeAt(secret, testClock.instant()));
    }

    @Test
    void preloadedCredentialCannotBypassReplayAfterAnotherTransactionCommits() {
        Actor user = actor();
        Enrollment enrollment = mfa.beginEnrollment(user, PASSWORD);
        mfa.confirmEnrollment(user, Totp.codeAt(enrollment.secret(), NOW));
        testClock.set(NOW.plusSeconds(120));
        String code = Totp.codeAt(enrollment.secret(), testClock.instant());
        var transaction = new TransactionTemplate(transactions);
        DomainException rejection = assertThrows(DomainException.class, () ->
                transaction.executeWithoutResult(ignored -> {
                    users.findById(user.userId()).orElseThrow();
                    credentialRepository.findById(user.userId()).orElseThrow();
                    try (var executor = Executors.newSingleThreadExecutor()) {
                        executor.submit(() -> mfa.verifyLogin(user.userId(), 1, code)).get(15, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new AssertionError("Independent MFA verification did not commit", failure);
                    }
                    mfa.verifyLogin(user.userId(), 1, code);
                }));
        assertEquals("invalid_mfa_code", rejection.code());
    }

    @Test
    void repeatedTotpInOneCallerTransactionIsDeniedAndFailureRollsBackConsumption() {
        Actor user = actor();
        Enrollment enrollment = mfa.beginEnrollment(user, PASSWORD);
        mfa.confirmEnrollment(user, Totp.codeAt(enrollment.secret(), NOW));
        testClock.set(NOW.plusSeconds(120));
        String code = Totp.codeAt(enrollment.secret(), testClock.instant());
        var transaction = new TransactionTemplate(transactions);
        DomainException rejection = assertThrows(DomainException.class, () ->
                transaction.executeWithoutResult(ignored -> {
                    mfa.verifyLogin(user.userId(), 1, code);
                    mfa.verifyLogin(user.userId(), 1, code);
                }));
        assertEquals("invalid_mfa_code", rejection.code());
        // The whole enclosing operation failed, so its earlier consumption also rolled back.
        mfa.verifyLogin(user.userId(), 1, code);
        assertCode("invalid_mfa_code", () -> mfa.verifyLogin(user.userId(), 1, code));
    }

    private boolean tryLoginAfter(CountDownLatch start, Actor user, String code) throws InterruptedException {
        start.await();
        try {
            mfa.verifyLogin(user.userId(), 1, code);
            return true;
        } catch (DomainException error) {
            assertEquals("invalid_mfa_code", error.code());
            return false;
        }
    }

    private List<String> enroll(Actor user) {
        Enrollment enrollment = mfa.beginEnrollment(user, PASSWORD);
        return mfa.confirmEnrollment(user, Totp.codeAt(enrollment.secret(), testClock.instant()));
    }

    private long authVersion(Actor user) {
        return jdbc.queryForObject("SELECT auth_version FROM app_users WHERE id = ?", Long.class, user.userId());
    }

    private void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(DomainException.class, action::run).code());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableClock mfaTestClock() { return new MutableClock(); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(NOW);
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instant.get(); }
    }
}
