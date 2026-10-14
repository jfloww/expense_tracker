CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT user_email_normalized CHECK (email = lower(btrim(email)) AND length(email) > 0),
    CONSTRAINT user_display_name_valid CHECK (length(btrim(display_name)) > 0)
);

CREATE TABLE households (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT household_name_valid CHECK (length(btrim(name)) > 0)
);

CREATE TABLE memberships (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    role VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT membership_role_valid CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT membership_household_user_unique UNIQUE (household_id, user_id)
);
CREATE INDEX membership_user_idx ON memberships(user_id);

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE RESTRICT,
    name VARCHAR(120) NOT NULL,
    issuer VARCHAR(20) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    last_four VARCHAR(4),
    currency VARCHAR(3) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT account_name_valid CHECK (length(btrim(name)) > 0),
    CONSTRAINT account_issuer_valid CHECK (issuer IN ('CAPITAL_ONE', 'CHASE', 'AMEX', 'OTHER')),
    CONSTRAINT account_kind_valid CHECK (kind IN ('CREDIT_CARD', 'BANK_ACCOUNT')),
    CONSTRAINT account_last_four_valid CHECK (last_four IS NULL OR last_four ~ '^[0-9]{4}$'),
    CONSTRAINT account_currency_supported CHECK (currency = 'USD')
);
CREATE INDEX account_household_idx ON accounts(household_id);
