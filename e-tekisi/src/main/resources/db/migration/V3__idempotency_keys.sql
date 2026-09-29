-- V3: Idempotency keys for retry-safe writes (Phase 1.9).
--
-- Stores a small fingerprint of every successful response keyed by a client-
-- supplied Idempotency-Key + route. Identical retries return the cached
-- response_hash without re-executing side effects (booking create, fare
-- accept, trip complete).
--
-- TTL is enforced by a scheduled cleanup; the unique constraint is what makes
-- the row a hard contract. The store is intentionally append-only at the
-- application level — only the cleanup job deletes.

CREATE SCHEMA IF NOT EXISTS agent_schema;

CREATE TABLE IF NOT EXISTS agent_schema.idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    route           VARCHAR(128) NOT NULL,
    response_hash   VARCHAR(128) NOT NULL,
    response_body   TEXT,
    status_code     INT NOT NULL DEFAULT 200,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_key_route UNIQUE (idempotency_key, route)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_keys_expires
    ON agent_schema.idempotency_keys (expires_at);
