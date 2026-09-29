-- V4: Per-trip planned route polyline (Phase 3.1).
--
-- A trip can have at most one route entry. The polyline is encoded as a
-- compact JSON array of [lat,lng] doubles (small enough to fit in TEXT for
-- the city-scale trips Tekisi serves).
--
-- The PolylineExpectedRoute reads this row at safety-evaluation time. When
-- no row exists the safety agent falls back to the existing ALWAYS_ON_PATH
-- expected-route — i.e. route-deviation detection degrades gracefully to
-- "no judgment" rather than to "false alarm."

CREATE SCHEMA IF NOT EXISTS agent_schema;

CREATE TABLE IF NOT EXISTS agent_schema.trip_routes (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      BIGINT NOT NULL,
    polyline_json   TEXT NOT NULL,
    point_count     INT NOT NULL DEFAULT 0,
    distance_meters DOUBLE PRECISION,
    source          VARCHAR(32) NOT NULL DEFAULT 'ROUTING_API',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    correlation_id  VARCHAR(128),
    CONSTRAINT uq_trip_routes_booking UNIQUE (booking_id)
);

CREATE INDEX IF NOT EXISTS idx_trip_routes_booking
    ON agent_schema.trip_routes (booking_id);
