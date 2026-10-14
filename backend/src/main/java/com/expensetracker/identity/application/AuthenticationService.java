package com.expensetracker.identity.application;

import com.expensetracker.identity.domain.User;
import com.expensetracker.identity.persistence.UserRepository;
import com.expensetracker.identity.security.SessionPrincipal;
import com.expensetracker.identity.security.SessionAuthenticationAdapter.GoogleProof;
import com.expensetracker.mfa.application.MfaService;
import com.expensetracker.shared.error.DomainException;
import java.util.UUID;
import java.time.Clock;
import jakarta.persistence.EntityManager;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {
    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final MfaService mfa;
    private final EntityManager entityManager;
    private final Clock clock;

    public AuthenticationService(AuthenticationManager authenticationManager, UserRepository users,
            PasswordEncoder passwords, MfaService mfa, EntityManager entityManager, Clock clock) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.passwords = passwords;
        this.mfa = mfa;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public LoginIdentity authenticate(String email, String password) {
        if (!PasswordPolicy.bounded(password) || password.isBlank()) throw invalidCredentials();
        String normalized;
        try { normalized = User.normalizeEmail(email); }
        catch (DomainException invalid) { throw invalidCredentials(); }
        // Lock before credential lookup can load a managed User; refresh after a lock wait.
        var locked = users.findLockedByEmail(normalized);
        locked.ifPresent(entityManager::refresh);
        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(normalized, password));
        } catch (AuthenticationException rejected) {
            // Disabled/passwordless users still incur the same password work as an unknown identity.
            if (locked.isPresent() && (!locked.get().isEnabled() || !locked.get().isPasswordLoginEnabled())) {
                passwords.matches(password, locked.get().getPasswordHash());
            }
            throw invalidCredentials();
        }
        var user = locked.orElseThrow(AuthenticationService::invalidCredentials);
        if (!user.isEnabled()) throw invalidCredentials();
        return new LoginIdentity(principal(user), view(user), mfa.isEnabled(user.getId()));
    }

    @Transactional(readOnly = true)
    public UserView current(SessionPrincipal principal) {
        return view(requireCurrent(principal));
    }

    @Transactional(readOnly = true)
    public boolean sessionIsCurrent(SessionPrincipal principal) {
        return users.findById(principal.userId()).filter(user -> user.isEnabled()
                && user.getAuthVersion() == principal.authVersion()).isPresent();
    }

    @Transactional
    public LoginIdentity completeLogin(UUID userId, long expectedVersion) {
        var user = users.findLockedById(userId).orElseThrow(AuthenticationService::invalidCredentials);
        entityManager.refresh(user);
        if (!user.isEnabled() || user.getAuthVersion() != expectedVersion) throw invalidCredentials();
        return new LoginIdentity(principal(user), view(user), false);
    }

    @Transactional(readOnly = true)
    public SessionPrincipal refreshedPrincipal(Actor actor, long expectedVersion) {
        var user = users.findById(actor.userId()).filter(candidate -> candidate.isEnabled()
                        && candidate.getAuthVersion() == expectedVersion)
                .orElseThrow(AuthenticationService::invalidCredentials);
        return principal(user);
    }

    @Transactional
    public void changePassword(SessionPrincipal principal, String currentPassword, String newPassword, String code) {
        var user = lockedCurrent(principal);
        requirePassword(user, currentPassword);
        PasswordPolicy.validate(newPassword);
        if (passwords.matches(newPassword, user.getPasswordHash())) throw DomainException.invalid("password_unchanged");
        if (mfa.isEnabled(user.getId())) mfa.verifyLogin(user.getId(), user.getAuthVersion(), code);
        user.changePasswordHash(passwords.encode(newPassword));
    }

    @Transactional
    public void requireReauthentication(SessionPrincipal principal, String password, String code) {
        var user = lockedCurrent(principal);
        requirePassword(user, password);
        if (mfa.isEnabled(user.getId())) mfa.verifyLogin(user.getId(), user.getAuthVersion(), code);
    }

    @Transactional
    public void setInitialPassword(SessionPrincipal principal, GoogleProof proof, String newPassword, String code) {
        var user = lockedCurrent(principal);
        if (proof == null || !proof.userId().equals(user.getId())
                || proof.authVersion() != user.getAuthVersion() || !proof.expiresAt().isAfter(clock.instant())) {
            throw DomainException.unauthenticated("google_reauthentication_required");
        }
        if (user.isPasswordLoginEnabled()) throw DomainException.conflict("password_already_set");
        PasswordPolicy.validate(newPassword);
        if (mfa.isEnabled(user.getId())) mfa.verifyLogin(user.getId(), user.getAuthVersion(), code);
        user.changePasswordHash(passwords.encode(newPassword));
    }

    private User lockedCurrent(SessionPrincipal principal) {
        if (principal == null) throw invalidCredentials();
        var user = users.findLockedById(principal.userId()).orElseThrow(AuthenticationService::invalidCredentials);
        entityManager.refresh(user);
        if (!user.isEnabled() || user.getAuthVersion() != principal.authVersion()) throw invalidCredentials();
        return user;
    }

    private User requireCurrent(SessionPrincipal principal) {
        if (principal == null) throw invalidCredentials();
        return users.findById(principal.userId()).filter(user -> user.isEnabled()
                && user.getAuthVersion() == principal.authVersion()).orElseThrow(AuthenticationService::invalidCredentials);
    }

    private void requirePassword(User user, String password) {
        if (!PasswordPolicy.bounded(password) || !passwords.matches(password, user.getPasswordHash())) throw invalidCredentials();
    }

    private static DomainException invalidCredentials() { return DomainException.unauthenticated("invalid_credentials"); }
    private static SessionPrincipal principal(User user) {
        return new SessionPrincipal(user.getId(), user.getEmail(), user.getAuthVersion());
    }
    private static UserView view(User user) {
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt(), user.isPasswordLoginEnabled());
    }
    public record LoginIdentity(SessionPrincipal principal, UserView user, boolean mfaRequired) {}
}
