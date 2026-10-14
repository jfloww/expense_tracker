package com.expensetracker.identity.application;

import com.expensetracker.shared.error.DomainException;
import com.expensetracker.identity.security.SessionPrincipal;
import com.expensetracker.identity.persistence.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Self-service sessions only; public IDs are independent of reusable cookie session secrets. */
@Service
public class SessionService {
    private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(12);
    private final IdentityAccess identities;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final JdbcTemplate database;
    private final UserRepository users;
    private final EntityManager entities;

    public SessionService(IdentityAccess identities,
            FindByIndexNameSessionRepository<? extends Session> sessions, JdbcTemplate database,
            UserRepository users, EntityManager entities) {
        this.identities = identities;
        this.sessions = sessions;
        this.database = database;
        this.users = users;
        this.entities = entities;
    }

    @Transactional(readOnly = true)
    public List<SessionView> list(Actor actor, String currentSessionId) {
        var user = identities.requireEnabledUser(actor);
        var indexed = sessions.findByPrincipalName(actor.userId().toString());
        var now = Instant.now();
        return database.query("""
                SELECT primary_id, session_id FROM SPRING_SESSION
                WHERE principal_name = ? AND expiry_time > ?
                """, (row, index) -> {
                    var session = indexed.get(row.getString("session_id"));
                    if (session == null || session.isExpired()) {
                        return null;
                    }
                    SecurityContext context = session.getAttribute(
                            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                    if (context == null || context.getAuthentication() == null
                            || !(context.getAuthentication().getPrincipal() instanceof SessionPrincipal principal)
                            || !actor.userId().equals(principal.userId())
                            || user.getAuthVersion() != principal.authVersion()) {
                        return null;
                    }
                    var idleExpiry = session.getLastAccessedTime().plus(session.getMaxInactiveInterval());
                    var absoluteExpiry = session.getCreationTime().plus(ABSOLUTE_LIFETIME);
                    var expiresAt = idleExpiry.isBefore(absoluteExpiry) ? idleExpiry : absoluteExpiry;
                    if (!expiresAt.isAfter(now)) {
                        return null;
                    }
                    return new SessionView(UUID.fromString(row.getString("primary_id").trim()),
                            session.getId().equals(currentSessionId), session.getCreationTime(),
                            session.getLastAccessedTime(), expiresAt);
                }, actor.userId().toString(), now.toEpochMilli()).stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(SessionView::createdAt).reversed()
                        .thenComparing(SessionView::id))
                .toList();
    }

    @Transactional
    public void revoke(Actor actor, long expectedVersion, UUID id) {
        requireCurrent(actor, expectedVersion);
        revoke(actor, id);
    }

    @Transactional
    public void revoke(Actor actor, UUID id) {
        identities.requireEnabledUser(actor);
        if (id == null || database.update("""
                DELETE FROM SPRING_SESSION WHERE primary_id = ? AND principal_name = ?
                """, id.toString(), actor.userId().toString()) != 1) {
            throw DomainException.notFound();
        }
    }

    @Transactional
    public int revokeOthers(Actor actor, long expectedVersion, String currentSessionId) {
        requireCurrent(actor, expectedVersion);
        return revokeOthers(actor, currentSessionId);
    }

    @Transactional
    public int revokeOthers(Actor actor, String currentSessionId) {
        identities.requireEnabledUser(actor);
        var current = database.query("""
                SELECT primary_id FROM SPRING_SESSION
                WHERE session_id = ? AND principal_name = ? AND expiry_time > ?
                """, (row, index) -> row.getString("primary_id"), currentSessionId,
                actor.userId().toString(), Instant.now().toEpochMilli());
        if (current.size() != 1) {
            throw DomainException.notFound();
        }
        return database.update("""
                DELETE FROM SPRING_SESSION WHERE principal_name = ? AND primary_id <> ?
                """, actor.userId().toString(), current.getFirst());
    }

    public record SessionView(UUID id, boolean current, Instant createdAt,
            Instant lastAccessedAt, Instant expiresAt) {}

    private void requireCurrent(Actor actor, long expectedVersion) {
        if (actor == null) throw DomainException.unauthenticated("invalid_credentials");
        var user = users.findLockedById(actor.userId())
                .orElseThrow(() -> DomainException.unauthenticated("invalid_credentials"));
        entities.flush();
        entities.refresh(user);
        if (!user.isEnabled() || user.getAuthVersion() != expectedVersion) {
            throw DomainException.unauthenticated("invalid_credentials");
        }
    }
}
