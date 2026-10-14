package com.expensetracker.foundation;

import com.expensetracker.accounts.application.CreateAccountCommand;
import com.expensetracker.accounts.application.UpdateAccountCommand;
import com.expensetracker.accounts.domain.AccountIssuer;
import com.expensetracker.accounts.domain.AccountKind;
import com.expensetracker.households.domain.Role;
import com.expensetracker.shared.error.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityServicesIT extends FoundationTestSupport {
    @Autowired private PasswordEncoder passwords;

    @Test
    void registrationNormalizesIdentityAndReturnsNoPasswordCredentials() {
        String password = "Synthetic-password-2026";
        var result = identities.register("  Synthetic.User@EXAMPLE.TEST  ", "  Synthetic user  ", password);
        var stored = users.findById(result.id()).orElseThrow();

        assertEquals("synthetic.user@example.test", result.email());
        assertEquals("Synthetic user", result.displayName());
        assertEquals(result.email(), stored.getEmail());
        assertNotEquals(password, stored.getPasswordHash());
        assertTrue(passwords.matches(password, stored.getPasswordHash()));
        assertFalse(result.toString().contains(password));
        assertFalse(result.toString().contains(stored.getPasswordHash()));
        assertTrue(stored.isEnabled());

        assertDomainError(DomainException.Kind.CONFLICT, "email_already_registered",
                () -> identities.register("SYNTHETIC.USER@example.test", "Another name", password));
        assertEquals(1, users.count());
        assertEquals(result.id(), users.findByEmail(result.email()).orElseThrow().getId());
    }

    @Test
    void passwordsOverBcryptByteLimitAreRejectedBeforeAnyUserIsStored() {
        for (String password : List.of("a".repeat(73), "\uD55C".repeat(25))) {
            assertDomainError(DomainException.Kind.INVALID, "invalid_password",
                    () -> identities.register("synthetic@example.test", "Synthetic user", password));
        }
        assertEquals(0, users.count());
    }

    @Test
    void seventyTwoBytePasswordsAreStoredAndCanBeVerifiedWithoutTruncation() {
        for (String password : List.of("a".repeat(72), "\uD55C".repeat(24))) {
            var result = identities.register("synthetic-" + users.count() + "@example.test",
                    "Synthetic user", password);
            assertTrue(passwords.matches(password, users.findById(result.id()).orElseThrow().getPasswordHash()));
        }
        assertEquals(2, users.count());
    }

    @Test
    void emailControlCharactersAreDomainErrorsInsteadOfDatabaseEncodingFailures() {
        for (int control : List.of(0, 1, 127, 133)) {
            String email = "synthetic" + Character.toString(control) + "@example.test";
            assertDomainError(DomainException.Kind.INVALID, "invalid_email",
                    () -> identities.register(email, "Synthetic user", "Synthetic-password-2026"));
        }
        assertEquals(0, users.count());
    }

    @Test
    void disabledIdentityCannotReadOrMutateHouseholdDataDespiteAnExistingMembership() {
        var shared = household();
        var member = member(shared.id(), Role.MEMBER);
        var command = new CreateAccountCommand("Shared card", AccountIssuer.CHASE,
                AccountKind.CREDIT_CARD, "1234", "USD");
        var existing = accounts.create(shared.owner(), shared.id(), command);
        assertEquals(1, jdbc.update("UPDATE app_users SET enabled = FALSE WHERE id = ?", shared.owner().userId()));

        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> householdQueries.listFor(shared.owner()));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> householdQueries.get(shared.owner(), shared.id()));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> households.create(shared.owner(), "Unauthorized new household"));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> households.changeRole(shared.owner(), shared.id(), member.userId(), Role.OWNER));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> households.removeMember(shared.owner(), shared.id(), member.userId()));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> households.leave(shared.owner(), shared.id()));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accountQueries.list(shared.owner(), shared.id()));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accountQueries.get(shared.owner(), shared.id(), existing.id()));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accounts.create(shared.owner(), shared.id(), command));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accounts.update(shared.owner(), shared.id(), existing.id(),
                        new UpdateAccountCommand("Unauthorized edit", AccountIssuer.AMEX,
                                AccountKind.CREDIT_CARD, "5678", "USD", false)));

        assertEquals(1, householdRepository.count());
        assertEquals(2, memberships.count());
        assertEquals(Role.MEMBER, householdQueries.get(member, shared.id()).role());
        assertEquals(List.of(existing), accountQueries.list(member, shared.id()));
    }
}
