package com.expensetracker.foundation;

import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.oauth.GoogleClaims;
import com.expensetracker.identity.oauth.GoogleIdentityService;
import com.expensetracker.identity.security.SessionPrincipal;
import com.expensetracker.mfa.application.MfaService;
import com.expensetracker.mfa.domain.Totp;
import com.expensetracker.shared.error.DomainException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleIdentityServicesIT extends FoundationTestSupport {
    @Autowired private GoogleIdentityService google;
    @Autowired private MfaService mfa;
    @Autowired private Clock clock;

    @Test
    void googleSubjectIsStableWhileProviderEmailAndDisplayNameMayChange() {
        String subject = UUID.randomUUID().toString();
        var created = google.login(claims(subject, "first@example.test"));
        var user = users.findById(created.principal().userId()).orElseThrow();
        assertFalse(user.isPasswordLoginEnabled());
        assertFalse(created.mfaRequired());
        assertEquals("first@example.test", created.user().email());

        var again = google.login(claims(subject, "changed@example.test"));
        assertEquals(created.user().id(), again.user().id());
        assertEquals("first@example.test", again.user().email());
        assertEquals(1, users.count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM google_identities", Integer.class));
    }

    @Test
    void invalidOrUnverifiedClaimsCannotCreateOrIdentifyAnAccount() {
        var invalid = new GoogleClaims[]{
                new GoogleClaims(GoogleIdentityService.ISSUER, "subject", "synthetic@example.test", false, "Name"),
                new GoogleClaims("https://attacker.example", "subject", "synthetic@example.test", true, "Name"),
                claims("", "synthetic@example.test"), claims("x".repeat(256), "synthetic@example.test"),
                claims("unicode-\u2603", "synthetic@example.test"), claims("subject", "invalid-email")};
        for (var value : invalid) {
            assertError("invalid_google_identity", () -> google.login(value));
        }
        assertEquals(0, users.count());
    }

    @Test
    void matchingEmailRequiresExplicitLinkAndTheActorAndVersionRemainAuthoritative() {
        Actor local = actor();
        var user = users.findById(local.userId()).orElseThrow();
        var claims = claims("synthetic-subject", user.getEmail());
        assertError("oauth_account_link_required", () -> google.login(claims));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM google_identities", Integer.class));

        Actor other = actor();
        assertError("oauth_email_mismatch", () -> google.link(principal(other, 0), claims));
        assertError("invalid_google_identity", () -> google.link(principal(local, 1), claims));
        var linked = google.link(principal(local, 0), claims);
        assertEquals(local.userId(), linked.principal().userId());
        assertEquals(1, linked.principal().authVersion());
        assertTrue(users.findById(local.userId()).orElseThrow().isPasswordLoginEnabled());
        assertError("oauth_identity_already_linked", () -> google.link(principal(local, 1), claims));
        assertEquals(1, users.findById(local.userId()).orElseThrow().getAuthVersion());
        assertEquals(local.userId(), google.login(claims).principal().userId());
    }

    @Test
    void oneSubjectCannotBeMovedToAnotherUserAndDisabledUsersCannotUseGoogle() {
        var initial = google.login(claims("stable-subject", "initial@example.test"));
        Actor another = actor();
        var anotherUser = users.findById(another.userId()).orElseThrow();
        assertError("oauth_identity_already_linked", () -> google.link(principal(another, 0),
                claims("stable-subject", anotherUser.getEmail())));
        assertEquals(initial.principal().userId(), google.login(claims("stable-subject", "other@example.test"))
                .principal().userId());
        jdbc.update("UPDATE app_users SET enabled = FALSE WHERE id = ?", initial.principal().userId());
        assertError("invalid_google_identity", () -> google.login(claims("stable-subject", "initial@example.test")));
    }

    @Test
    void linkingAndGoogleLoginPreserveTheLocalMfaGate() {
        Actor local = actor();
        var user = users.findById(local.userId()).orElseThrow();
        var setup = mfa.beginEnrollment(local, "Synthetic-password-2026");
        mfa.confirmEnrollment(local, Totp.codeAt(setup.secret(), clock.instant()));
        var claims = claims("mfa-subject", user.getEmail());
        var linked = google.link(principal(local, 1), claims);
        assertEquals(2, linked.principal().authVersion());
        assertTrue(linked.mfaRequired());
        assertTrue(google.login(claims).mfaRequired());
    }

    private SessionPrincipal principal(Actor actor, long version) {
        return new SessionPrincipal(actor.userId(), users.findById(actor.userId()).orElseThrow().getEmail(), version);
    }

    private static GoogleClaims claims(String subject, String email) {
        return new GoogleClaims(GoogleIdentityService.ISSUER, subject, email, true, "Synthetic Google user");
    }

    private static void assertError(String code, Runnable action) {
        assertEquals(code, assertThrows(DomainException.class, action::run).code());
    }
}
