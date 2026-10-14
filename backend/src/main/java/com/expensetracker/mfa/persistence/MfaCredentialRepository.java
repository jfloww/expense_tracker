package com.expensetracker.mfa.persistence;

import com.expensetracker.mfa.domain.MfaCredential;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MfaCredentialRepository extends JpaRepository<MfaCredential, UUID> {}
