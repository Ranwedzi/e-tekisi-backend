-- V17: "Share my trip". A commuter sends a link to someone they trust, who
-- can follow the taxi until the trip ends. Only a SHA-256 hash of the link's
-- token is stored.

CREATE TABLE IF NOT EXISTS commuter_schema.trip_shares (
    id          BIGSERIAL PRIMARY KEY,
    booking_id  BIGINT      NOT NULL REFERENCES commuter_schema.bookings (id),
    created_by  BIGINT      NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_trip_shares_booking ON commuter_schema.trip_shares (booking_id);
