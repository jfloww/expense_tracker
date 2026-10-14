-- Spring Session JDBC's PostgreSQL schema. PRIMARY_ID is an independent public
-- session-management handle; SESSION_ID is the reusable authentication secret.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL PRIMARY KEY,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

-- Only digests are stored: no submitted email address or client network address.
-- Each bucket keeps one fixed window beginning at its first admitted attempt.
CREATE TABLE auth_attempt_limits (
    bucket_hash CHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL,
    CONSTRAINT auth_attempt_bucket_hash CHECK (bucket_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT auth_attempt_count_valid CHECK (attempts > 0 AND attempts <= 10001),
    CONSTRAINT auth_attempt_window_valid CHECK (expires_at > window_start)
);
CREATE INDEX auth_attempt_limits_expiry_idx ON auth_attempt_limits(expires_at);
