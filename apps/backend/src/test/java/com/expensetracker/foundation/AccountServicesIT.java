package com.expensetracker.foundation;

import com.expensetracker.accounts.application.CreateAccountCommand;
import com.expensetracker.accounts.application.UpdateAccountCommand;
import com.expensetracker.accounts.domain.AccountIssuer;
import com.expensetracker.accounts.domain.AccountKind;
import com.expensetracker.households.domain.Role;
import com.expensetracker.shared.error.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServicesIT extends FoundationTestSupport {
    private CreateAccountCommand accountCommand(String name) {
        return new CreateAccountCommand(name, AccountIssuer.CHASE, AccountKind.CREDIT_CARD, "1234", "USD");
    }

    private UpdateAccountCommand updateCommand(String name) {
        return new UpdateAccountCommand(name, AccountIssuer.AMEX, AccountKind.CREDIT_CARD, "5678", "USD", false);
    }

    @Test
    void ownersAndMembersCanCreateReadAndUpdateSharedAccounts() {
        var shared = household();
        var member = member(shared.id(), Role.MEMBER);
        var ownerAccount = accounts.create(shared.owner(), shared.id(), accountCommand("Owner card"));
        var memberAccount = accounts.create(member, shared.id(), accountCommand("  Shared card  "));

        assertEquals("Shared card", memberAccount.name());
        assertEquals(shared.id(), memberAccount.householdId());
        assertEquals(2, accountQueries.list(shared.owner(), shared.id()).size());
        assertEquals(ownerAccount.id(), accountQueries.get(member, shared.id(), ownerAccount.id()).id());

        var updated = accounts.update(member, shared.id(), ownerAccount.id(), updateCommand("Updated card"));
        assertEquals("Updated card", updated.name());
        assertEquals(AccountIssuer.AMEX, updated.issuer());
        assertEquals("5678", updated.lastFour());
        assertFalse(updated.active());
        assertEquals(updated, accountQueries.get(shared.owner(), shared.id(), ownerAccount.id()));
    }

    @Test
    void nonmembersCannotReadCreateOrUpdateAccounts() {
        var shared = household();
        var stranger = actor();
        var existing = accounts.create(shared.owner(), shared.id(), accountCommand("Private card"));

        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accountQueries.list(stranger, shared.id()));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accountQueries.get(stranger, shared.id(), existing.id()));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accounts.create(stranger, shared.id(), accountCommand("Unauthorized card")));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accounts.update(stranger, shared.id(), existing.id(), updateCommand("Unauthorized rename")));
        assertEquals(List.of(existing), accountQueries.list(shared.owner(), shared.id()));
    }

    @Test
    void memberRemovalRevokesAccessAndPreservesTheSharedAccount() {
        var shared = household();
        var member = member(shared.id(), Role.MEMBER);
        var created = accounts.create(member, shared.id(), accountCommand("Member-created card"));

        households.removeMember(shared.owner(), shared.id(), member.userId());

        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accountQueries.get(member, shared.id(), created.id()));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accounts.update(member, shared.id(), created.id(), updateCommand("Revoked edit")));
        assertEquals(created.id(), accountQueries.get(shared.owner(), shared.id(), created.id()).id());
        assertEquals(1, accountQueries.list(shared.owner(), shared.id()).size());
    }

    @Test
    void departureRevokesMembershipAndPreservesAccounts() {
        var shared = household();
        var member = member(shared.id(), Role.MEMBER);
        var created = accounts.create(member, shared.id(), accountCommand("Shared after departure"));

        households.leave(member, shared.id());

        assertTrue(householdQueries.listFor(member).isEmpty());
        assertEquals(created.id(), accountQueries.get(shared.owner(), shared.id(), created.id()).id());
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> accounts.create(member, shared.id(), accountCommand("After departure")));
    }

    @Test
    void foreignAccountCannotBeSubstitutedEvenWhenActorBelongsToBothHouseholds() {
        var first = household();
        var second = households.create(first.owner(), "Second household");
        var foreign = accounts.create(first.owner(), second.id(), accountCommand("Second-household card"));

        assertDomainError(DomainException.Kind.NOT_FOUND, "not_found",
                () -> accountQueries.get(first.owner(), first.id(), foreign.id()));
        assertDomainError(DomainException.Kind.NOT_FOUND, "not_found",
                () -> accounts.update(first.owner(), first.id(), foreign.id(), updateCommand("Mis-scoped edit")));

        assertTrue(accountQueries.list(first.owner(), first.id()).isEmpty());
        assertEquals(foreign, accountQueries.get(first.owner(), second.id(), foreign.id()));
    }

    @Test
    void invalidServiceCommandsCannotWritePartialOrUnsupportedAccounts() {
        var shared = household();
        var invalid = List.of(
                accountCommand("   "),
                accountCommand("n".repeat(121)),
                new CreateAccountCommand("Card", null, AccountKind.CREDIT_CARD, "1234", "USD"),
                new CreateAccountCommand("Card", AccountIssuer.CHASE, null, "1234", "USD"),
                new CreateAccountCommand("Card", AccountIssuer.CHASE, AccountKind.CREDIT_CARD, "12x4", "USD"),
                new CreateAccountCommand("Card", AccountIssuer.CHASE, AccountKind.CREDIT_CARD, "1234", "EUR"));

        for (var command : invalid) {
            assertDomainError(DomainException.Kind.INVALID, null,
                    () -> accounts.create(shared.owner(), shared.id(), command));
        }
        assertTrue(accountQueries.list(shared.owner(), shared.id()).isEmpty());

        var existing = accounts.create(shared.owner(), shared.id(), accountCommand("Unchanged card"));
        var unsupported = new UpdateAccountCommand("Changed card", AccountIssuer.AMEX,
                AccountKind.CREDIT_CARD, "5678", "EUR", false);
        assertDomainError(DomainException.Kind.INVALID, null,
                () -> accounts.update(shared.owner(), shared.id(), existing.id(), unsupported));
        assertEquals(existing, accountQueries.get(shared.owner(), shared.id(), existing.id()));
    }
}
