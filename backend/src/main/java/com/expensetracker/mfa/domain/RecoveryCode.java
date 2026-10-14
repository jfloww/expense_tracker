package com.expensetracker.mfa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mfa_recovery_codes")
public class RecoveryCode {
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    protected RecoveryCode() {}

    public static RecoveryCode create(UUID userId, String codeHash) {
        var code = new RecoveryCode();
        code.id = UUID.randomUUID();
        code.userId = userId;
        code.codeHash = codeHash;
        return code;
    }

    public boolean isUsed() { return usedAt != null; }
    public void consume(Instant now) { usedAt = now; }
}
