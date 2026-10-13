package com.expensetracker.foundation;

import com.expensetracker.accounts.application.AccountQueryService;
import com.expensetracker.accounts.application.AccountService;
import com.expensetracker.households.application.HouseholdQueryService;
import com.expensetracker.households.application.HouseholdService;
import com.expensetracker.households.domain.Membership;
import com.expensetracker.households.domain.Role;
import com.expensetracker.households.persistence.HouseholdRepository;
import com.expensetracker.households.persistence.MembershipRepository;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.application.IdentityService;
import com.expensetracker.identity.persistence.UserRepository;
import com.expensetracker.shared.error.DomainException;
import com.expensetracker.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Fixtures call application services; the repository is used only to bootstrap memberships. */
abstract class FoundationTestSupport extends PostgresIntegrationTest {
    @Autowired protected IdentityService identities;
    @Autowired protected HouseholdService households;
    @Autowired protected HouseholdQueryService householdQueries;
    @Autowired protected HouseholdRepository householdRepository;
    @Autowired protected MembershipRepository memberships;
    @Autowired protected UserRepository users;
    @Autowired protected AccountService accounts;
    @Autowired protected AccountQueryService accountQueries;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void resetFoundationData() {
        cleanDatabase();
    }

    protected Actor actor() {
        var user = identities.register("synthetic-" + UUID.randomUUID() + "@example.test",
                "Synthetic user", "Synthetic-password-2026");
        return new Actor(user.id());
    }

    protected HouseholdFixture household() {
        var owner = actor();
        var created = households.create(owner, "Synthetic household");
        return new HouseholdFixture(owner, created.id());
    }

    protected Actor member(UUID householdId, Role role) {
        var actor = actor();
        memberships.saveAndFlush(Membership.create(
                householdRepository.findById(householdId).orElseThrow(),
                users.findById(actor.userId()).orElseThrow(), role));
        return actor;
    }

    protected void assertDomainError(DomainException.Kind kind, String code, Runnable action) {
        var error = assertThrows(DomainException.class, action::run);
        assertEquals(kind, error.kind());
        if (code != null) {
            assertEquals(code, error.code());
        }
    }

    protected record HouseholdFixture(Actor owner, UUID id) {}
}
