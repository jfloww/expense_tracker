package com.expensetracker.households.application;

import com.expensetracker.households.domain.Household;
import com.expensetracker.households.domain.Membership;
import com.expensetracker.households.domain.Role;
import com.expensetracker.households.persistence.HouseholdRepository;
import com.expensetracker.households.persistence.MembershipRepository;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.application.IdentityAccess;
import com.expensetracker.identity.domain.User;
import com.expensetracker.shared.error.DomainException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HouseholdService {
    private final HouseholdRepository households;
    private final MembershipRepository memberships;
    private final IdentityAccess identities;
    private final HouseholdAccess access;

    public HouseholdService(HouseholdRepository households, MembershipRepository memberships,
            IdentityAccess identities, HouseholdAccess access) {
        this.households = households;
        this.memberships = memberships;
        this.identities = identities;
        this.access = access;
    }

    @Transactional
    public HouseholdView create(Actor actor, String name) {
        User owner = identities.requireEnabledUser(actor);
        Household household = households.saveAndFlush(Household.create(name));
        memberships.saveAndFlush(Membership.create(household, owner, Role.OWNER));
        return new HouseholdView(household.getId(), household.getName(), Role.OWNER, household.getCreatedAt());
    }

    @Transactional
    public void changeRole(Actor actor, UUID householdId, UUID targetUserId, Role role) {
        access.lockAndRequireOwner(actor, householdId);
        if (role == null) {
            throw DomainException.invalid("invalid_role");
        }
        Membership target = requireTarget(householdId, targetUserId);
        if (target.getRole() == Role.OWNER && role != Role.OWNER) {
            requireAnotherOwner(householdId);
        }
        target.changeRole(role);
    }

    @Transactional
    public void removeMember(Actor actor, UUID householdId, UUID targetUserId) {
        access.lockAndRequireOwner(actor, householdId);
        remove(householdId, requireTarget(householdId, targetUserId));
    }

    @Transactional
    public void leave(Actor actor, UUID householdId) {
        access.lockAndRequireMember(actor, householdId);
        remove(householdId, requireTarget(householdId, actor.userId()));
    }

    private Membership requireTarget(UUID householdId, UUID targetUserId) {
        if (targetUserId == null) {
            throw DomainException.invalid("invalid_user_id");
        }
        return memberships.findByHouseholdIdAndUserId(householdId, targetUserId)
                .orElseThrow(DomainException::notFound);
    }

    private void remove(UUID householdId, Membership target) {
        if (target.getRole() == Role.OWNER) {
            requireAnotherOwner(householdId);
        }
        // Membership removal never cascades to household accounts or ledger records.
        memberships.delete(target);
    }

    private void requireAnotherOwner(UUID householdId) {
        if (memberships.countByHouseholdIdAndRole(householdId, Role.OWNER) <= 1) {
            throw DomainException.conflict("last_owner");
        }
    }
}
