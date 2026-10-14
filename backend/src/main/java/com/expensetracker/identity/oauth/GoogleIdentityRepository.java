package com.expensetracker.identity.oauth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoogleIdentityRepository extends JpaRepository<GoogleIdentity, UUID> {
    Optional<GoogleIdentity> findByIssuerAndSubject(String issuer, String subject);
    boolean existsByUserIdAndIssuer(UUID userId, String issuer);
}
