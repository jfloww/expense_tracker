package com.expensetracker.foundation;

import com.expensetracker.households.domain.Role;
import com.expensetracker.shared.error.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseholdServicesIT extends FoundationTestSupport {
    @Test
    void creationPersistsHouseholdAndOwnerMembership() {
        var owner = actor();
        var created = households.create(owner, "  Our household  ");

        assertEquals("Our household", created.name());
        assertEquals(Role.OWNER, created.role());
        assertEquals(created.id(), householdQueries.get(owner, created.id()).id());
        assertEquals(1, memberships.countByHouseholdIdAndRole(created.id(), Role.OWNER));
        assertEquals(1, householdRepository.count());
        assertEquals(1, memberships.count());
    }

    @Test
    void readsRequireCurrentMembershipAndListOnlyActorsHouseholds() {
        var shared = household();
        var other = household();
        var member = member(shared.id(), Role.MEMBER);

        assertEquals(Role.MEMBER, householdQueries.get(member, shared.id()).role());
        assertEquals(1, householdQueries.listFor(member).size());
        assertEquals(shared.id(), householdQueries.listFor(member).getFirst().id());
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> householdQueries.get(member, other.id()));

        households.removeMember(shared.owner(), shared.id(), member.userId());
        assertTrue(householdQueries.listFor(member).isEmpty());
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> householdQueries.get(member, shared.id()));
    }

    @Test
    void membersCannotManageRolesOrRemoveOtherMembers() {
        var shared = household();
        var member = member(shared.id(), Role.MEMBER);
        var target = member(shared.id(), Role.MEMBER);

        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> households.changeRole(member, shared.id(), target.userId(), Role.OWNER));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> households.removeMember(member, shared.id(), target.userId()));
        assertEquals(Role.MEMBER,
                memberships.findByHouseholdIdAndUserId(shared.id(), target.userId()).orElseThrow().getRole());
    }

    @Test
    void ownerCanPromoteDemoteAndRemoveMembers() {
        var shared = household();
        var target = member(shared.id(), Role.MEMBER);

        households.changeRole(shared.owner(), shared.id(), target.userId(), Role.OWNER);
        assertEquals(Role.OWNER, householdQueries.get(target, shared.id()).role());
        households.changeRole(shared.owner(), shared.id(), target.userId(), Role.MEMBER);
        assertEquals(Role.MEMBER, householdQueries.get(target, shared.id()).role());
        households.removeMember(shared.owner(), shared.id(), target.userId());

        assertFalse(memberships.findByHouseholdIdAndUserId(shared.id(), target.userId()).isPresent());
        assertEquals(1, memberships.countByHouseholdIdAndRole(shared.id(), Role.OWNER));
    }

    @Test
    void lastOwnerCannotLeaveBeDemotedOrBeRemoved() {
        var shared = household();

        assertDomainError(DomainException.Kind.CONFLICT, "last_owner",
                () -> households.leave(shared.owner(), shared.id()));
        assertDomainError(DomainException.Kind.CONFLICT, "last_owner",
                () -> households.changeRole(shared.owner(), shared.id(), shared.owner().userId(), Role.MEMBER));
        assertDomainError(DomainException.Kind.CONFLICT, "last_owner",
                () -> households.removeMember(shared.owner(), shared.id(), shared.owner().userId()));

        assertEquals(Role.OWNER, householdQueries.get(shared.owner(), shared.id()).role());
        assertEquals(1, memberships.countByHouseholdIdAndRole(shared.id(), Role.OWNER));
    }

    @Test
    void ownerRoleInAnotherHouseholdDoesNotGrantManagementAccess() {
        var first = household();
        var second = household();
        var target = member(second.id(), Role.MEMBER);

        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> households.changeRole(first.owner(), second.id(), target.userId(), Role.OWNER));
        assertDomainError(DomainException.Kind.FORBIDDEN, "forbidden",
                () -> households.removeMember(first.owner(), second.id(), target.userId()));
        assertEquals(Role.MEMBER, householdQueries.get(target, second.id()).role());
    }

    @Test
    void serviceValidatesCommandsWithoutAnHttpController() {
        var owner = actor();
        assertDomainError(DomainException.Kind.INVALID, null, () -> households.create(owner, "   "));
        assertDomainError(DomainException.Kind.INVALID, null,
                () -> households.create(owner, "h".repeat(121)));
        assertEquals(0, householdRepository.count());

        var shared = households.create(owner, "Valid household");
        var target = member(shared.id(), Role.MEMBER);
        assertDomainError(DomainException.Kind.INVALID, null,
                () -> households.changeRole(owner, shared.id(), target.userId(), null));
        assertEquals(Role.MEMBER, householdQueries.get(target, shared.id()).role());
    }
}
