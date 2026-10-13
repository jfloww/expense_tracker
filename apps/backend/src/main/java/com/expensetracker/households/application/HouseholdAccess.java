package com.expensetracker.households.application;

import com.expensetracker.households.domain.Household;
import com.expensetracker.households.domain.Membership;
import com.expensetracker.households.domain.Role;
import com.expensetracker.households.persistence.HouseholdRepository;
import com.expensetracker.households.persistence.MembershipRepository;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.application.IdentityAccess;
import com.expensetracker.shared.error.DomainException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The common authorization and lock protocol for household-scoped operations. */
@Service
public class HouseholdAccess {
    private final HouseholdRepository households;
    private final MembershipRepository memberships;
    private final IdentityAccess identities;

    public HouseholdAccess(HouseholdRepository households, MembershipRepository memberships, IdentityAccess identities) {
        this.households = households;
        this.memberships = memberships;
        this.identities = identities;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Household lockAndRequireMember(Actor actor, UUID householdId) {
        Household household = lock(householdId);
        authorize(actor, householdId);
        return household;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Household lockAndRequireOwner(Actor actor, UUID householdId) {
        Household household = lock(householdId);
        if (authorize(actor, householdId).getRole() != Role.OWNER) {
            throw DomainException.forbidden();
        }
        return household;
    }

    @Transactional(readOnly = true)
    public Membership requireMember(Actor actor, UUID householdId) {
        requireHouseholdId(householdId);
        return authorize(actor, householdId);
    }

    private Household lock(UUID householdId) {
        requireHouseholdId(householdId);
        return households.findLockedById(householdId).orElseThrow(DomainException::notFound);
    }

    private Membership authorize(Actor actor, UUID householdId) {
        identities.requireEnabledUser(actor);
        return memberships.findByHouseholdIdAndUserId(householdId, actor.userId())
                .orElseThrow(DomainException::forbidden);
    }

    private static void requireHouseholdId(UUID householdId) {
        if (householdId == null) {
            throw DomainException.invalid("invalid_household_id");
        }
    }
}
