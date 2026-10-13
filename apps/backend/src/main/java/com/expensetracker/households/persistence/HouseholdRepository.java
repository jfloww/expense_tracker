package com.expensetracker.households.persistence;

import com.expensetracker.households.domain.Household;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseholdRepository extends JpaRepository<Household, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Household h where h.id = :id")
    Optional<Household> findLockedById(@Param("id") UUID id);
}
