package com.expensetracker.households.persistence;

import com.expensetracker.households.domain.Membership;
import com.expensetracker.households.domain.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    Optional<Membership> findByHouseholdIdAndUserId(UUID householdId, UUID userId);

    long countByHouseholdIdAndRole(UUID householdId, Role role);

    @EntityGraph(attributePaths = "household")
    List<Membership> findByUserIdOrderByCreatedAtAsc(UUID userId);
}
