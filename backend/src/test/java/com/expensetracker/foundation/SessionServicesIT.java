package com.expensetracker.foundation;

import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.application.SessionService;
import com.expensetracker.identity.security.SessionPrincipal;
import com.expensetracker.shared.error.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class SessionServicesIT extends FoundationTestSupport {
    @Autowired private SessionService service;
    @Autowired private JdbcIndexedSessionRepository sessions;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void listUsesOpaqueHandlesAndSurvivesRepositoryRecreation() {
        var owner = actor();
        var current = persistedSession(owner);
        var other = persistedSession(owner);
        var stranger = persistedSession(actor());

        var result = service.list(owner, current.getId());
        assertEquals(2, result.size());
        assertEquals(1, result.stream().filter(SessionService.SessionView::current).count());
        assertTrue(result.stream().noneMatch(view -> view.id().toString().equals(current.getId())
                || view.id().toString().equals(other.getId()) || view.id().toString().equals(stranger.getId())));
        assertTrue(result.stream().allMatch(view -> view.expiresAt().isAfter(view.createdAt())));

        var freshRepository = new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(transactions));
        SessionRepository<?> freshSessions = freshRepository;
        Session restored = freshSessions.findById(current.getId());
        assertNotNull(restored);
        var context = (org.springframework.security.core.context.SecurityContext) restored.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertEquals(owner.userId(), ((SessionPrincipal) context.getAuthentication().getPrincipal()).userId());
        freshRepository.destroy();
    }

    @Test
    void revokeIsScopedAndItsOpaqueHandleSurvivesCookieRotation() {
        var owner = actor();
        var session = persistedSession(owner);
        var handle = service.list(owner, session.getId()).getFirst().id();
        var stranger = actor();
        assertDomainError(DomainException.Kind.NOT_FOUND, "not_found", () -> service.revoke(stranger, handle));
        assertNotNull(sessionApi().findById(session.getId()));

        var originalCookie = session.getId();
        Session rotated = sessionApi().findById(originalCookie);
        var changedCookie = rotated.changeSessionId();
        sessionApi().save(rotated);
        assertNotEquals(originalCookie, changedCookie);
        assertEquals(handle, service.list(owner, changedCookie).getFirst().id());
        service.revoke(owner, handle);
        // A request that loaded the session before revocation cannot resurrect it on save.
        rotated.setLastAccessedTime(Instant.now());
        sessionApi().save(rotated);
        assertNull(sessionApi().findById(changedCookie));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM SPRING_SESSION_ATTRIBUTES", Integer.class));
        assertDomainError(DomainException.Kind.NOT_FOUND, "not_found", () -> service.revoke(owner, handle));
    }

    @Test
    void revokeOthersKeepsCurrentAndForeignSessions() {
        var owner = actor();
        var current = persistedSession(owner);
        var other = persistedSession(owner);
        var foreign = persistedSession(actor());
        assertDomainError(DomainException.Kind.NOT_FOUND, "not_found",
                () -> service.revokeOthers(owner, foreign.getId()));
        assertEquals(2, service.list(owner, current.getId()).size());

        assertEquals(1, service.revokeOthers(owner, current.getId()));
        assertNotNull(sessionApi().findById(current.getId()));
        assertNull(sessionApi().findById(other.getId()));
        assertNotNull(sessionApi().findById(foreign.getId()));
        assertEquals(0, service.revokeOthers(owner, current.getId()));
    }

    @Test
    void disabledIdentityCannotReadOrRevokeSessions() {
        var owner = actor();
        var current = persistedSession(owner);
        var handle = service.list(owner, current.getId()).getFirst().id();
        jdbc.update("UPDATE app_users SET enabled = false WHERE id = ?", owner.userId());
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden", () -> service.list(owner, current.getId()));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden", () -> service.revoke(owner, handle));
        assertNotNull(sessionApi().findById(current.getId()));
    }

    @Test
    void listExcludesStaleAuthenticationAndAbsoluteOrIdleExpiredSessions() {
        var owner = actor();
        var stale = persistedSession(owner);
        jdbc.update("UPDATE app_users SET auth_version = auth_version + 1 WHERE id = ?", owner.userId());
        assertTrue(service.list(owner, stale.getId()).isEmpty());

        var fresh = persistedSession(owner);
        var idleExpired = persistedSession(owner);
        jdbc.update("UPDATE SPRING_SESSION SET expiry_time = ? WHERE session_id = ?",
                Instant.now().minusSeconds(1).toEpochMilli(), idleExpired.getId());
        var absoluteExpired = persistedSession(owner);
        jdbc.update("UPDATE SPRING_SESSION SET creation_time = ? WHERE session_id = ?",
                Instant.now().minus(Duration.ofHours(13)).toEpochMilli(), absoluteExpired.getId());

        var result = service.list(owner, fresh.getId());
        assertEquals(1, result.size());
        assertTrue(result.getFirst().current());
    }

    private Session persistedSession(Actor actor) {
        var user = users.findById(actor.userId()).orElseThrow();
        Session session = sessionApi().createSession();
        session.setMaxInactiveInterval(Duration.ofMinutes(30));
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, actor.userId().toString());
        var principal = new SessionPrincipal(actor.userId(), user.getEmail(), user.getAuthVersion());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        sessionApi().save(session);
        return session;
    }

    /** The JDBC implementation's session class is package-private; use its public contract. */
    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessionApi() {
        // Only instances created/loaded by this same repository are submitted to save().
        return (SessionRepository<Session>) (SessionRepository<?>) sessions;
    }
}
