package com.expensetracker.identity.security;

import com.expensetracker.shared.error.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SessionProofTest {
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    @Test void googleProofIsBoundToIdentityVersionAndFiveMinuteDeadline() {
        var request = new MockHttpServletRequest();
        var principal = new SessionPrincipal(UUID.randomUUID(),"synthetic@example.test",1);
        var adapter = adapter(NOW);
        assertThrows(DomainException.class, () -> adapter.requireRecentGoogle(principal,request));
        adapter.markGoogle(principal,request);
        assertDoesNotThrow(() -> adapter.requireRecentGoogle(principal,request));
        assertThrows(DomainException.class, () -> adapter.requireRecentGoogle(
                new SessionPrincipal(UUID.randomUUID(),principal.email(),1),request));
        assertThrows(DomainException.class, () -> adapter.requireRecentGoogle(
                new SessionPrincipal(principal.userId(),principal.email(),2),request));
        assertThrows(DomainException.class, () -> adapter(NOW.plusSeconds(300)).requireRecentGoogle(principal,request));
    }
    @Test void googleMfaChallengeStartsWithoutGrantingGooglePasswordSetupProof() {
        var request = new MockHttpServletRequest();
        var principal = new SessionPrincipal(UUID.randomUUID(),"synthetic@example.test",1);
        var adapter = adapter(NOW);
        adapter.markGoogle(principal,request);
        adapter.startGoogleChallenge(principal,request);
        assertTrue(adapter.pending(request).google());
        assertThrows(DomainException.class, () -> adapter.requireRecentGoogle(principal,request));
        assertThrows(DomainException.class, () -> adapter(NOW.plusSeconds(300)).pending(request));
    }
    private static SessionAuthenticationAdapter adapter(Instant now) {
        return new SessionAuthenticationAdapter(mock(SecurityContextRepository.class),
                mock(SessionAuthenticationStrategy.class),mock(CsrfTokenRepository.class),Clock.fixed(now,ZoneOffset.UTC));
    }
}
