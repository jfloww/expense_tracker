package com.expensetracker.mfa.persistence;

import com.expensetracker.mfa.domain.RecoveryCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, UUID> {
    Optional<RecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(UUID userId, String codeHash);
    long countByUserIdAndUsedAtIsNull(UUID userId);

    @Modifying
    @Query("delete from RecoveryCode code where code.userId = :userId")
    void deleteForUser(@Param("userId") UUID userId);
}
