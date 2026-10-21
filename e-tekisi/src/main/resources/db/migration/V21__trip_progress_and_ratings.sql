-- V21: Where a trip starts and ends (coordinates), how it progresses (driver
-- arrived, trip started, completed), and ratings both ways afterwards.

ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS pickup_lat      DOUBLE PRECISION;
ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS pickup_lng      DOUBLE PRECISION;
ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS destination_lat DOUBLE PRECISION;
ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS destination_lng DOUBLE PRECISION;
ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS arrived_at      TIMESTAMPTZ;
ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS started_at      TIMESTAMPTZ;
ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS completed_at    TIMESTAMPTZ;

-- Running totals, so an average can be shown without scanning every rating.
ALTER TABLE commuter_schema.users  ADD COLUMN IF NOT EXISTS rating_sum   INT NOT NULL DEFAULT 0;
ALTER TABLE commuter_schema.users  ADD COLUMN IF NOT EXISTS rating_count INT NOT NULL DEFAULT 0;
ALTER TABLE driver_schema.drivers  ADD COLUMN IF NOT EXISTS rating_sum   INT NOT NULL DEFAULT 0;
ALTER TABLE driver_schema.drivers  ADD COLUMN IF NOT EXISTS rating_count INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS commuter_schema.trip_ratings (
    id          BIGSERIAL PRIMARY KEY,
    booking_id  BIGINT       NOT NULL REFERENCES commuter_schema.bookings (id),
    rater_role  VARCHAR(16)  NOT NULL,
    rater_id    BIGINT       NOT NULL,
    ratee_role  VARCHAR(16)  NOT NULL,
    ratee_id    BIGINT       NOT NULL,
    stars       SMALLINT     NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment     VARCHAR(300),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_trip_ratings_once UNIQUE (booking_id, rater_role)
);
CREATE INDEX IF NOT EXISTS idx_trip_ratings_ratee ON commuter_schema.trip_ratings (ratee_role, ratee_id, created_at);
