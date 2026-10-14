CREATE TABLE mfa_credentials (
    user_id UUID PRIMARY KEY REFERENCES app_users(id) ON DELETE RESTRICT,
    secret_ciphertext VARCHAR(512),
    pending_secret_ciphertext VARCHAR(512),
    enrollment_expires_at TIMESTAMPTZ,
    enrollment_auth_version BIGINT,
    last_used_step BIGINT NOT NULL DEFAULT -1 CHECK (last_used_step >= -1),
    CONSTRAINT mfa_enrollment_complete CHECK (
        (pending_secret_ciphertext IS NULL AND enrollment_expires_at IS NULL AND enrollment_auth_version IS NULL)
        OR (pending_secret_ciphertext IS NOT NULL AND enrollment_expires_at IS NOT NULL
            AND enrollment_auth_version IS NOT NULL AND enrollment_auth_version >= 0)
    ),
    CONSTRAINT mfa_active_not_pending CHECK (secret_ciphertext IS NULL OR pending_secret_ciphertext IS NULL)
);

CREATE TABLE mfa_recovery_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    code_hash VARCHAR(64) NOT NULL CHECK (code_hash ~ '^[a-f0-9]{64}$'),
    used_at TIMESTAMPTZ,
    CONSTRAINT mfa_recovery_user_hash_unique UNIQUE (user_id, code_hash)
);
