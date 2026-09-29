-- V5: Persisted dispatch recommendations (Phase 3.3).
--
-- Records every autonomous-dispatch attempt, whether or not the policy
-- engine permitted it to mutate the booking. Used during the canary phase
-- (shadow → 5% → 25% → 100%) to compare agent recommendations against the
-- real human-driver acceptance pattern.
--
-- Status semantics:
--   PENDING   — operator confirmation required before mutating the booking.
--   EXECUTED  — the agent assigned a driver under autonomous policy.
--   REJECTED  — the policy engine refused to authorise (degraded / low confidence).
--   EXPIRED   — operator did not act within the canary window.

CREATE SCHEMA IF NOT EXISTS agent_schema;

CREATE TABLE IF NOT EXISTS agent_schema.dispatch_recommendation (
    id                   BIGSERIAL PRIMARY KEY,
    booking_id           BIGINT NOT NULL,
    recommended_driver_id BIGINT,
    confidence           DOUBLE PRECISION NOT NULL,
    reason_codes         VARCHAR(512),
    status               VARCHAR(32) NOT NULL,
    correlation_id       VARCHAR(128),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at           TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_dispatch_rec_booking
    ON agent_schema.dispatch_recommendation (booking_id);

CREATE INDEX IF NOT EXISTS idx_dispatch_rec_status_created
    ON agent_schema.dispatch_recommendation (status, created_at);
