package com.expensetracker.identity.oauth;

import com.expensetracker.identity.application.AuthenticationService.LoginIdentity;
import com.expensetracker.identity.application.UserView;
import com.expensetracker.identity.domain.User;
import com.expensetracker.identity.persistence.UserRepository;
import com.expensetracker.identity.security.SessionPrincipal;
import com.expensetracker.mfa.application.MfaService;
import com.expensetracker.shared.error.DomainException;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoogleIdentityService {
    public static final String ISSUER = "https://accounts.google.com";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final GoogleIdentityRepository identities;
    private final PasswordEncoder passwords;
    private final MfaService mfa;
    private final EntityManager entities;
    private final Clock clock;

    public GoogleIdentityService(UserRepository users, GoogleIdentityRepository identities,
            PasswordEncoder passwords, MfaService mfa, EntityManager entities, Clock clock) {
        this.users = users; this.identities = identities; this.passwords = passwords;
        this.mfa = mfa; this.entities = entities; this.clock = clock;
    }

    @Transactional
    public LoginIdentity login(GoogleClaims claims) {
        String email = validate(claims);
        var binding = identities.findByIssuerAndSubject(ISSUER, claims.subject());
        if (binding.isPresent()) return result(lockEnabled(binding.get().getUserId()));
        if (users.findByEmail(email).isPresent()) {
            throw DomainException.conflict("oauth_account_link_required");
        }
        User user = User.create(email, displayName(claims), passwords.encode(randomPassword()));
        user.disablePasswordLogin();
        users.saveAndFlush(user);
        identities.saveAndFlush(GoogleIdentity.create(user.getId(), ISSUER, claims.subject(), clock.instant()));
        return result(user);
    }

    /** The caller has completed local password/MFA step-up and supplies its short-lived server marker. */
    @Transactional
    public LoginIdentity link(SessionPrincipal principal, GoogleClaims claims) {
        String email = validate(claims);
        if (principal == null) throw invalid();
        User user = lockEnabled(principal.userId());
        if (user.getAuthVersion() != principal.authVersion()) throw invalid();
        if (!user.getEmail().equals(email)) {
            throw DomainException.conflict("oauth_email_mismatch");
        }
        if (identities.findByIssuerAndSubject(ISSUER, claims.subject()).isPresent()
                || identities.existsByUserIdAndIssuer(user.getId(), ISSUER)) {
            throw DomainException.conflict("oauth_identity_already_linked");
        }
        identities.saveAndFlush(GoogleIdentity.create(user.getId(), ISSUER, claims.subject(), clock.instant()));
        user.bumpAuthVersion();
        return result(user);
    }

    private User lockEnabled(UUID id) {
        User user = users.findLockedById(id).orElseThrow(GoogleIdentityService::invalid);
        // Preserve earlier changes from an enclosing caller before refreshing after a lock wait.
        entities.flush();
        entities.refresh(user);
        if (!user.isEnabled()) throw invalid();
        return user;
    }

    private LoginIdentity result(User user) {
        return new LoginIdentity(new SessionPrincipal(user.getId(), user.getEmail(), user.getAuthVersion()),
                new UserView(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt(),
                        user.isPasswordLoginEnabled()),
                mfa.isEnabled(user.getId()));
    }

    private static String validate(GoogleClaims claims) {
        if (claims == null || !ISSUER.equals(claims.issuer()) || !claims.emailVerified()
                || claims.subject() == null || !claims.subject().matches("[!-~]{1,255}")) throw invalid();
        try { return User.normalizeEmail(claims.email()); }
        catch (DomainException rejected) { throw invalid(); }
    }

    private static String displayName(GoogleClaims claims) {
        try { return User.validateDisplayName(claims.displayName()); }
        catch (DomainException absentOrInvalid) { return "Google user"; }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private static DomainException invalid() { return DomainException.unauthenticated("invalid_google_identity"); }
}
