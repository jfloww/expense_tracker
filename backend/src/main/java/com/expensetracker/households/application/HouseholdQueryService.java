package com.expensetracker.households.application;

import com.expensetracker.households.domain.Household;
import com.expensetracker.households.domain.Membership;
import com.expensetracker.households.persistence.MembershipRepository;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.application.IdentityAccess;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class HouseholdQueryService {
    private final MembershipRepository memberships;
    private final IdentityAccess identities;
    private final HouseholdAccess access;

    public HouseholdQueryService(MembershipRepository memberships, IdentityAccess identities, HouseholdAccess access) {
        this.memberships = memberships;
        this.identities = identities;
        this.access = access;
    }

    public List<HouseholdView> listFor(Actor actor) {
        identities.requireEnabledUser(actor);
        return memberships.findByUserIdOrderByCreatedAtAsc(actor.userId()).stream()
                .map(HouseholdQueryService::view).toList();
    }

    public HouseholdView get(Actor actor, UUID householdId) {
        return view(access.requireMember(actor, householdId));
    }

    private static HouseholdView view(Membership membership) {
        Household household = membership.getHousehold();
        return new HouseholdView(household.getId(), household.getName(), membership.getRole(), household.getCreatedAt());
    }
}
