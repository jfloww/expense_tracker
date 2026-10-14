package com.expensetracker.mfa.application;

import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.application.PasswordPolicy;
import com.expensetracker.identity.domain.User;
import com.expensetracker.identity.persistence.UserRepository;
import com.expensetracker.mfa.domain.MfaCredential;
import com.expensetracker.mfa.domain.RecoveryCode;
import com.expensetracker.mfa.domain.RecoveryCodes;
import com.expensetracker.mfa.domain.Totp;
import com.expensetracker.mfa.persistence.MfaCredentialRepository;
import com.expensetracker.mfa.persistence.RecoveryCodeRepository;
import com.expensetracker.mfa.security.MfaSecretCipher;
import com.expensetracker.shared.error.DomainException;
import jakarta.persistence.EntityManager;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MFA security mutations serialize on the user's row; they never touch household data. */
@Service
public class MfaService {
    private static final Duration ENROLLMENT_LIFETIME = Duration.ofMinutes(10);
    private final UserRepository users;
    private final MfaCredentialRepository credentials;
    private final RecoveryCodeRepository recoveryCodes;
    private final PasswordEncoder passwords;
    private final MfaSecretCipher cipher;
    private final Clock clock;
    private final EntityManager entities;

    public MfaService(UserRepository users, MfaCredentialRepository credentials,
                      RecoveryCodeRepository recoveryCodes, PasswordEncoder passwords,
                      MfaSecretCipher cipher, Clock clock, EntityManager entities) {
        this.users = users;
        this.credentials = credentials;
        this.recoveryCodes = recoveryCodes;
        this.passwords = passwords;
        this.cipher = cipher;
        this.clock = clock;
        this.entities = entities;
    }

    @Transactional(readOnly = true)
    public MfaStatus status(Actor actor) {
        User user = requireUser(actor, false);
        MfaCredential credential = credentials.findById(user.getId()).orElse(null);
        boolean enabled = credential != null && credential.isEnabled();
        boolean pending = credential != null && credential.getEnrollmentExpiresAt() != null
                && credential.getEnrollmentExpiresAt().isAfter(clock.instant())
                && Objects.equals(credential.getEnrollmentAuthVersion(), user.getAuthVersion());
        return new MfaStatus(enabled, enabled
                ? Math.toIntExact(recoveryCodes.countByUserIdAndUsedAtIsNull(user.getId())) : 0,
                pending, pending ? credential.getEnrollmentExpiresAt() : null);
    }

    @Transactional
    public Enrollment beginEnrollment(Actor actor, long expectedAuthVersion, String currentPassword) {
        requireExpectedVersion(actor, expectedAuthVersion);
        return beginEnrollment(actor, currentPassword);
    }

    /** Trusted internal callers supply their current actor; HTTP must use the version-aware overload. */
    @Transactional
    public Enrollment beginEnrollment(Actor actor, String currentPassword) {
        User user = requireUser(actor, true);
        requirePassword(user, currentPassword);
        MfaCredential credential = currentCredential(user.getId())
                .orElseGet(() -> MfaCredential.forUser(user.getId()));
        if (credential.isEnabled()) {
            throw DomainException.conflict("mfa_already_enabled");
        }
        String secret = Totp.newSecret();
        Instant expiresAt = clock.instant().plus(ENROLLMENT_LIFETIME);
        credential.beginEnrollment(cipher.encrypt(user.getId(), secret), expiresAt, user.getAuthVersion());
        credentials.save(credential);
        String label = encode("Expense Tracker:" + user.getEmail());
        String uri = "otpauth://totp/" + label + "?secret=" + secret
                + "&issuer=" + encode("Expense Tracker") + "&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(secret, uri, expiresAt);
    }

    @Transactional
    public List<String> confirmEnrollment(Actor actor, long expectedAuthVersion, String code) {
        requireExpectedVersion(actor, expectedAuthVersion);
        return confirmEnrollment(actor, code);
    }

    @Transactional
    public List<String> confirmEnrollment(Actor actor, String code) {
        User user = requireUser(actor, true);
        MfaCredential credential = currentCredential(user.getId())
                .orElseThrow(() -> DomainException.invalid("mfa_enrollment_required"));
        if (credential.isEnabled()) {
            throw DomainException.conflict("mfa_already_enabled");
        }
        if (credential.getPendingSecretCiphertext() == null
                || !Objects.equals(credential.getEnrollmentAuthVersion(), user.getAuthVersion())) {
            throw DomainException.invalid("mfa_enrollment_required");
        }
        Instant now = clock.instant();
        if (!credential.getEnrollmentExpiresAt().isAfter(now)) {
            throw DomainException.invalid("mfa_enrollment_expired");
        }
        long step = Totp.match(cipher.decrypt(user.getId(), credential.getPendingSecretCiphertext()),
                code, now, -1).orElseThrow(() -> DomainException.unauthenticated("invalid_mfa_code"));
        credential.confirm(step);
        List<String> codes = replaceRecoveryCodes(user.getId());
        user.bumpAuthVersion();
        return codes;
    }

    @Transactional
    public void disable(Actor actor, long expectedAuthVersion, String currentPassword, String code) {
        requireExpectedVersion(actor, expectedAuthVersion);
        disable(actor, currentPassword, code);
    }

    @Transactional
    public void disable(Actor actor, String currentPassword, String code) {
        User user = requireUser(actor, true);
        requirePassword(user, currentPassword);
        MfaCredential credential = requireEnabledCredential(user.getId());
        verifyFactor(user.getId(), credential, code);
        recoveryCodes.deleteForUser(user.getId());
        credentials.delete(credential);
        user.bumpAuthVersion();
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(Actor actor, long expectedAuthVersion, String currentPassword, String code) {
        requireExpectedVersion(actor, expectedAuthVersion);
        return regenerateRecoveryCodes(actor, currentPassword, code);
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(Actor actor, String currentPassword, String code) {
        User user = requireUser(actor, true);
        requirePassword(user, currentPassword);
        verifyFactor(user.getId(), requireEnabledCredential(user.getId()), code);
        List<String> codes = replaceRecoveryCodes(user.getId());
        user.bumpAuthVersion();
        return codes;
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return credentials.findById(userId).map(MfaCredential::isEnabled).orElse(false);
    }

    @Transactional
    public void verifyLogin(UUID userId, long expectedAuthVersion, String code) {
        User user = lockEnabledUser(userId);
        if (user.getAuthVersion() != expectedAuthVersion) {
            throw DomainException.unauthenticated("invalid_credentials");
        }
        verifyFactor(userId, requireEnabledCredential(userId), code);
    }

    private User requireUser(Actor actor, boolean lock) {
        if (actor == null) {
            throw DomainException.unauthenticated("invalid_credentials");
        }
        if (lock) {
            return lockEnabledUser(actor.userId());
        }
        return users.findById(actor.userId()).filter(User::isEnabled)
                .orElseThrow(() -> DomainException.unauthenticated("invalid_credentials"));
    }

    private void requireExpectedVersion(Actor actor, long expectedAuthVersion) {
        User user = requireUser(actor, true);
        if (user.getAuthVersion() != expectedAuthVersion) {
            throw DomainException.unauthenticated("invalid_credentials");
        }
    }

    private User lockEnabledUser(UUID userId) {
        if (userId == null) {
            throw DomainException.unauthenticated("invalid_credentials");
        }
        User user = users.findLockedById(userId)
                .orElseThrow(() -> DomainException.unauthenticated("invalid_credentials"));
        // Preserve earlier authorized changes if the caller owns a larger transaction.
        entities.flush();
        // A surrounding transaction may have read the user before this lock waited.
        entities.refresh(user);
        if (!user.isEnabled()) {
            throw DomainException.unauthenticated("invalid_credentials");
        }
        return user;
    }

    private void requirePassword(User user, String password) {
        if (!PasswordPolicy.bounded(password) || !passwords.matches(password, user.getPasswordHash())) {
            throw DomainException.unauthenticated("invalid_credentials");
        }
    }

    private MfaCredential requireEnabledCredential(UUID userId) {
        return currentCredential(userId).filter(MfaCredential::isEnabled)
                .orElseThrow(() -> DomainException.unauthenticated("invalid_mfa_code"));
    }

    private Optional<MfaCredential> currentCredential(UUID userId) {
        var credential = credentials.findById(userId);
        credential.ifPresent(entities::refresh);
        return credential;
    }

    private void verifyFactor(UUID userId, MfaCredential credential, String code) {
        Instant now = clock.instant();
        if (RecoveryCodes.validFormat(code)) {
            RecoveryCode recovery = recoveryCodes.findByUserIdAndCodeHashAndUsedAtIsNull(userId, RecoveryCodes.hash(userId, code))
                    .orElseThrow(() -> DomainException.unauthenticated("invalid_mfa_code"));
            recovery.consume(now);
            return;
        }
        long acceptedStep = Totp.match(cipher.decrypt(userId, credential.getSecretCiphertext()),
                code, now, credential.getLastUsedStep())
                .orElseThrow(() -> DomainException.unauthenticated("invalid_mfa_code"));
        credential.consumeStep(acceptedStep);
    }

    private List<String> replaceRecoveryCodes(UUID userId) {
        recoveryCodes.deleteForUser(userId);
        List<String> codes = RecoveryCodes.generate();
        recoveryCodes.saveAll(codes.stream()
                .map(code -> RecoveryCode.create(userId, RecoveryCodes.hash(userId, code))).toList());
        return codes;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
