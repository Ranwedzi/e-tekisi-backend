-- V14: Account security. Email/phone verification, consent to the terms and
-- privacy policy, password reset, and signing out every session when the
-- password changes.

ALTER TABLE commuter_schema.users ADD COLUMN IF NOT EXISTS email_verified_at      TIMESTAMPTZ;
ALTER TABLE commuter_schema.users ADD COLUMN IF NOT EXISTS phone_verified_at      TIMESTAMPTZ;
ALTER TABLE commuter_schema.users ADD COLUMN IF NOT EXISTS terms_version          VARCHAR(40);
ALTER TABLE commuter_schema.users ADD COLUMN IF NOT EXISTS terms_accepted_at      TIMESTAMPTZ;
ALTER TABLE commuter_schema.users ADD COLUMN IF NOT EXISTS credentials_changed_at TIMESTAMPTZ;

ALTER TABLE driver_schema.drivers ADD COLUMN IF NOT EXISTS email_verified_at      TIMESTAMPTZ;
ALTER TABLE driver_schema.drivers ADD COLUMN IF NOT EXISTS phone_verified_at      TIMESTAMPTZ;
ALTER TABLE driver_schema.drivers ADD COLUMN IF NOT EXISTS terms_version          VARCHAR(40);
ALTER TABLE driver_schema.drivers ADD COLUMN IF NOT EXISTS terms_accepted_at      TIMESTAMPTZ;
ALTER TABLE driver_schema.drivers ADD COLUMN IF NOT EXISTS credentials_changed_at TIMESTAMPTZ;

-- Accounts created before verification existed are treated as verified, so
-- an upgrade doesn't lock anyone out. Their terms_version stays NULL: they
-- are asked to accept the current terms the next time they sign in.
UPDATE commuter_schema.users  SET email_verified_at = now() WHERE email_verified_at IS NULL AND deleted_at IS NULL;
UPDATE driver_schema.drivers  SET email_verified_at = now() WHERE email_verified_at IS NULL AND deleted_at IS NULL;

-- One-time secrets: email links (random 256-bit token) and SMS codes
-- (6 digits). Only a SHA-256 hash is stored; codes are hashed together
-- with the owner, so the same code for two people hashes differently.
CREATE TABLE IF NOT EXISTS commuter_schema.account_tokens (
    id          BIGSERIAL PRIMARY KEY,
    owner_id    BIGINT       NOT NULL,
    owner_role  VARCHAR(16)  NOT NULL,
    purpose     VARCHAR(24)  NOT NULL,
    channel     VARCHAR(8)   NOT NULL,
    secret_hash VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_account_tokens_secret ON commuter_schema.account_tokens (secret_hash);
CREATE INDEX IF NOT EXISTS idx_account_tokens_owner
    ON commuter_schema.account_tokens (owner_id, owner_role, purpose, created_at);
