package com.expensetracker.foundation;

import com.expensetracker.identity.application.AuthenticationService;
import com.expensetracker.identity.application.SessionService;
import com.expensetracker.identity.security.SessionPrincipal;
import com.expensetracker.identity.security.SessionAuthenticationAdapter;
import com.expensetracker.identity.security.SessionAuthenticationAdapter.GoogleProof;
import com.expensetracker.shared.error.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AuthenticationServicesIT extends FoundationTestSupport {
    @Autowired private AuthenticationService authentication;
    @Autowired private SessionService sessions;
    @Autowired private Clock clock;
    @Autowired private PasswordEncoder passwords;

    @Test void refreshedAuthenticationNeverAdoptsALaterRevocationVersion() {
        var actor = actor();
        jdbc.update("UPDATE app_users SET auth_version=2 WHERE id=?",actor.userId());
        var rejection = assertThrows(DomainException.class, () -> authentication.refreshedPrincipal(actor,1));
        assertEquals("invalid_credentials",rejection.code());
        assertEquals(2,authentication.refreshedPrincipal(actor,2).authVersion());
    }

    @Test void staleSessionCannotChangePasswordOrRevokeSessionsAfterSecurityVersionChanges() {
        var actor = actor(); var user = users.findById(actor.userId()).orElseThrow();
        var principal = new SessionPrincipal(actor.userId(),user.getEmail(),0);
        String original = user.getPasswordHash();
        jdbc.update("UPDATE app_users SET auth_version=1 WHERE id=?",actor.userId());
        assertThrows(DomainException.class, () -> authentication.changePassword(
                principal,"Synthetic-password-2026","Changed-synthetic-password-2026",null));
        assertEquals(original,users.findById(actor.userId()).orElseThrow().getPasswordHash());
        assertEquals("invalid_credentials",assertThrows(DomainException.class,
                () -> sessions.revoke(actor,0,UUID.randomUUID())).code());
        assertEquals("invalid_credentials",assertThrows(DomainException.class,
                () -> sessions.revokeOthers(actor,0,"synthetic-session")).code());
    }

    @Test void initialPasswordRequiresFreshIdentityAndVersionBoundGoogleProofInsideTheService() {
        var actor = actor();
        var user = users.findById(actor.userId()).orElseThrow();
        var principal = new SessionPrincipal(actor.userId(), user.getEmail(), 0);
        String originalHash = user.getPasswordHash();
        String newPassword = "Initial-synthetic-password-2026";
        jdbc.update("UPDATE app_users SET password_login_enabled = false WHERE id = ?", actor.userId());
        Instant issuedAt = clock.instant();
        GoogleProof[] rejectedProofs = {
                null,
                googleProof(new SessionPrincipal(UUID.randomUUID(), user.getEmail(), 0), issuedAt),
                googleProof(new SessionPrincipal(actor.userId(), user.getEmail(), 1), issuedAt),
                googleProof(principal, issuedAt.minusSeconds(300))
        };
        for (GoogleProof proof : rejectedProofs) {
            var rejected = assertThrows(DomainException.class,
                    () -> authentication.setInitialPassword(principal, proof, newPassword, null));
            assertEquals("google_reauthentication_required", rejected.code());
            var unchanged = users.findById(actor.userId()).orElseThrow();
            assertEquals(originalHash, unchanged.getPasswordHash());
            assertEquals(0, unchanged.getAuthVersion());
            assertFalse(unchanged.isPasswordLoginEnabled());
        }

        GoogleProof fresh = googleProof(principal, clock.instant());
        authentication.setInitialPassword(principal, fresh, newPassword, null);
        var changed = users.findById(actor.userId()).orElseThrow();
        assertTrue(changed.isPasswordLoginEnabled());
        assertTrue(passwords.matches(newPassword, changed.getPasswordHash()));
        assertEquals(1, changed.getAuthVersion());
        assertEquals("invalid_credentials", assertThrows(DomainException.class,
                () -> authentication.setInitialPassword(principal, fresh, newPassword, null)).code());
        var current = new SessionPrincipal(actor.userId(), user.getEmail(), 1);
        GoogleProof anotherFresh = googleProof(current, clock.instant());
        assertEquals("password_already_set", assertThrows(DomainException.class,
                () -> authentication.setInitialPassword(current, anotherFresh, "Other-synthetic-password", null)).code());
        assertEquals(1, users.findById(actor.userId()).orElseThrow().getAuthVersion());
    }

    private static GoogleProof googleProof(SessionPrincipal principal, Instant issuedAt) {
        var adapter = new SessionAuthenticationAdapter(mock(SecurityContextRepository.class),
                mock(SessionAuthenticationStrategy.class), mock(CsrfTokenRepository.class),
                Clock.fixed(issuedAt, ZoneOffset.UTC));
        var request = new MockHttpServletRequest();
        adapter.markGoogle(principal, request);
        return adapter.requireRecentGoogle(principal, request);
    }
}
