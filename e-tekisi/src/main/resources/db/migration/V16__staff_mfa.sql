-- V16: Two-factor sign-in (TOTP authenticator app) for operators and admins.
-- mfa_secret is encrypted with DATA_ENCRYPTION_KEY; recovery codes are stored
-- as SHA-256 hashes; mfa_last_step stops a code being used twice.

ALTER TABLE agent_schema.staff_users ADD COLUMN IF NOT EXISTS mfa_secret             VARCHAR(200);
ALTER TABLE agent_schema.staff_users ADD COLUMN IF NOT EXISTS mfa_enabled_at         TIMESTAMPTZ;
ALTER TABLE agent_schema.staff_users ADD COLUMN IF NOT EXISTS mfa_last_step          BIGINT;
ALTER TABLE agent_schema.staff_users ADD COLUMN IF NOT EXISTS mfa_recovery_hashes    VARCHAR(1000);
ALTER TABLE agent_schema.staff_users ADD COLUMN IF NOT EXISTS credentials_changed_at TIMESTAMPTZ;
