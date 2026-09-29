-- ============================================================================
-- Tekisi agent layer — initial Flyway baseline for agent_schema.
--
-- The agent layer owns its own schema. Existing commuter_schema and
-- driver_schema are managed elsewhere (currently still by JPA ddl-auto until a
-- follow-up phase migrates them too).
--
-- Idempotent CREATE TABLE statements are used so that environments where this
-- table was previously created by JPA ddl-auto do not break on first Flyway
-- run; the baseline matches the JPA entity exactly.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS agent_schema;

-- Authoritative per-trip GPS history. Read by SafetyAgent via TrustedDataService.
CREATE TABLE IF NOT EXISTS agent_schema.trip_gps_pings (
    id           BIGSERIAL    PRIMARY KEY,
    booking_id   BIGINT       NOT NULL,
    lat          DOUBLE PRECISION NOT NULL,
    lng          DOUBLE PRECISION NOT NULL,
    recorded_at  TIMESTAMP    NOT NULL,
    source       VARCHAR(32)
);

CREATE INDEX IF NOT EXISTS idx_trip_gps_booking_recorded
    ON agent_schema.trip_gps_pings (booking_id, recorded_at);
