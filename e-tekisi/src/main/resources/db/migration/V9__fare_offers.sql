-- V9: Fare offers. A driver's fare is now an offer the commuter must accept
-- before their wallet is charged; while OFFERED the booking is held for that
-- driver until offer_expires_at.

ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS offer_expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_bookings_offer_expiry
    ON commuter_schema.bookings (status, offer_expires_at);
