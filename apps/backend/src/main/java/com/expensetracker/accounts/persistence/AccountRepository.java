package com.expensetracker.accounts.persistence;

import com.expensetracker.accounts.domain.Account;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByIdAndHouseholdId(UUID id, UUID householdId);

    List<Account> findAllByHouseholdIdOrderByCreatedAtAscIdAsc(UUID householdId);
}
