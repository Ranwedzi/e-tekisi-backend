-- V22: Web Push subscriptions: one row per browser/phone that asked for
-- notifications. Deleted when the push service says the subscription is gone.

CREATE TABLE IF NOT EXISTS commuter_schema.push_subscriptions (
    id              BIGSERIAL PRIMARY KEY,
    owner_role      VARCHAR(16)   NOT NULL,
    owner_id        BIGINT        NOT NULL,
    endpoint        VARCHAR(1000) NOT NULL UNIQUE,
    p256dh          VARCHAR(200)  NOT NULL,
    auth            VARCHAR(100)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_success_at TIMESTAMPTZ,
    failures        INT           NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_push_subscriptions_owner ON commuter_schema.push_subscriptions (owner_role, owner_id);
