package com.expensetracker.identity.persistence;

import com.expensetracker.identity.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByIdAndEnabledTrue(UUID id);
}
