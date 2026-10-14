package com.expensetracker.identity.security;

import com.expensetracker.shared.error.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;
import java.util.List;

/** HTTP session choreography stays separate from transactional identity/MFA services. */
@Component
public class SessionAuthenticationAdapter {
    private static final String PENDING = SessionAuthenticationAdapter.class.getName() + ".PENDING";
    private static final String GOOGLE_PROOF = SessionAuthenticationAdapter.class.getName() + ".GOOGLE_PROOF";
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy strategy;
    private final CsrfTokenRepository csrf;
    private final Clock clock;

    public SessionAuthenticationAdapter(SecurityContextRepository contexts, SessionAuthenticationStrategy strategy,
            CsrfTokenRepository csrf, Clock clock) {
        this.contexts = contexts; this.strategy = strategy; this.csrf = csrf; this.clock = clock;
    }

    public void startChallenge(SessionPrincipal principal, HttpServletRequest request) {
        startChallenge(principal, request, false);
    }

    public void startGoogleChallenge(SessionPrincipal principal, HttpServletRequest request) {
        startChallenge(principal, request, true);
    }

    private void startChallenge(SessionPrincipal principal, HttpServletRequest request, boolean google) {
        var existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        SecurityContextHolder.clearContext();
        request.getSession(true).setAttribute(PENDING,
                new PendingLogin(principal.userId(), principal.authVersion(), clock.instant().plusSeconds(300), google));
    }

    public PendingLogin pending(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(PENDING) instanceof PendingLogin pending
                && pending.expiresAt().isAfter(clock.instant())) return pending;
        if (session != null) session.invalidate();
        throw DomainException.unauthenticated("login_challenge_expired");
    }

    public void signIn(SessionPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("USER")));
        strategy.onAuthentication(authentication, request, response);
        request.getSession(true).removeAttribute(PENDING);
        request.getSession(true).removeAttribute(GOOGLE_PROOF);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    public void markGoogle(SessionPrincipal principal, HttpServletRequest request) {
        request.getSession(true).setAttribute(GOOGLE_PROOF,
                new GoogleProof(principal.userId(), principal.authVersion(), clock.instant().plusSeconds(300)));
    }

    public GoogleProof requireRecentGoogle(SessionPrincipal principal, HttpServletRequest request) {
        var session = request.getSession(false);
        if (principal == null || session == null || !(session.getAttribute(GOOGLE_PROOF) instanceof GoogleProof proof)
                || !proof.userId().equals(principal.userId()) || proof.authVersion() != principal.authVersion()
                || !proof.expiresAt().isAfter(clock.instant())) {
            throw DomainException.unauthenticated("google_reauthentication_required");
        }
        return proof;
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        new CsrfLogoutHandler(csrf).logout(request, response, authentication);
        var logout = new SecurityContextLogoutHandler();
        logout.setSecurityContextRepository(contexts);
        logout.logout(request, response, authentication);
    }

    public record PendingLogin(UUID userId, long authVersion, Instant expiresAt, boolean google) implements Serializable {
        public PendingLogin(UUID userId, long authVersion, Instant expiresAt) { this(userId, authVersion, expiresAt, false); }
    }
    /** Server-created capability; application services still validate identity, version and expiry under lock. */
    public static final class GoogleProof implements Serializable {
        private static final long serialVersionUID = 1L;
        private final UUID userId;
        private final long authVersion;
        private final Instant expiresAt;

        private GoogleProof(UUID userId, long authVersion, Instant expiresAt) {
            this.userId = userId;
            this.authVersion = authVersion;
            this.expiresAt = expiresAt;
        }

        public UUID userId() { return userId; }
        public long authVersion() { return authVersion; }
        public Instant expiresAt() { return expiresAt; }
    }
}
