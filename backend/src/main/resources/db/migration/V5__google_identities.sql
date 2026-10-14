ALTER TABLE app_users ADD COLUMN password_login_enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE google_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    issuer VARCHAR(120) NOT NULL CHECK (issuer = 'https://accounts.google.com'),
    subject VARCHAR(255) NOT NULL CHECK (subject ~ '^[!-~]{1,255}$'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT google_identity_issuer_subject_unique UNIQUE (issuer, subject),
    CONSTRAINT google_identity_user_issuer_unique UNIQUE (user_id, issuer)
);
