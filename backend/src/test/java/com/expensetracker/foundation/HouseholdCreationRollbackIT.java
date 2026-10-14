package com.expensetracker.foundation;

import com.expensetracker.households.application.HouseholdService;
import com.expensetracker.households.domain.Membership;
import com.expensetracker.households.persistence.HouseholdRepository;
import com.expensetracker.households.persistence.MembershipRepository;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.application.IdentityService;
import com.expensetracker.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HouseholdCreationRollbackIT extends PostgresIntegrationTest {
    @Autowired private IdentityService identities;
    @Autowired private HouseholdService households;
    @Autowired private HouseholdRepository householdRepository;
    @MockitoSpyBean private MembershipRepository memberships;

    @BeforeEach
    void resetFoundationData() {
        cleanDatabase();
    }

    @Test
    void secondRecordFailureRollsBackCreationWithoutRemovingExistingHousehold() {
        var user = identities.register("rollback-" + UUID.randomUUID() + "@example.test",
                "Synthetic rollback user", "Synthetic-password-2026");
        var actor = new Actor(user.id());
        var existing = households.create(actor, "Existing household");
        doThrow(new IllegalStateException("synthetic-second-write-failure"))
                .when(memberships).saveAndFlush(any(Membership.class));

        var error = assertThrows(IllegalStateException.class,
                () -> households.create(actor, "Must roll back"));

        assertEquals("synthetic-second-write-failure", error.getMessage());
        assertEquals(1, householdRepository.count());
        assertEquals(existing.id(), householdRepository.findAll().getFirst().getId());
        assertEquals(1, memberships.count());
        verify(memberships, org.mockito.Mockito.times(2)).saveAndFlush(any(Membership.class));
    }
}
