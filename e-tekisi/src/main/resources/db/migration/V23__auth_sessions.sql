-- V23: browser sessions. The browser keeps a short-lived access token and a
-- refresh token in httpOnly cookies; only a hash of the refresh token is
-- stored. Each refresh replaces the token (same family); presenting a used
-- one again means it was copied, and the whole family is revoked.

CREATE TABLE IF NOT EXISTS commuter_schema.auth_sessions (
    id                BIGSERIAL PRIMARY KEY,
    family_id         VARCHAR(36)  NOT NULL,
    owner_role        VARCHAR(16)  NOT NULL,
    owner_id          BIGINT       NOT NULL,
    token_hash        VARCHAR(64)  NOT NULL UNIQUE,
    family_created_at TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    used_at           TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    user_agent        VARCHAR(200)
);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_owner ON commuter_schema.auth_sessions (owner_role, owner_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_family ON commuter_schema.auth_sessions (family_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_expires ON commuter_schema.auth_sessions (expires_at);
