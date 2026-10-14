ALTER TABLE app_users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_users ADD CONSTRAINT user_auth_version_valid CHECK (auth_version >= 0);
