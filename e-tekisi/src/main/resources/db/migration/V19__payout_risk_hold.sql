-- V19: Payouts the payment-risk agent flags are held: an operator can only
-- approve them after confirming they checked them, and who did is recorded.

ALTER TABLE driver_schema.payout_requests ADD COLUMN IF NOT EXISTS risk_hold       BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE driver_schema.payout_requests ADD COLUMN IF NOT EXISTS risk_reasons    VARCHAR(300);
ALTER TABLE driver_schema.payout_requests ADD COLUMN IF NOT EXISTS risk_cleared_by BIGINT;
ALTER TABLE driver_schema.payout_requests ADD COLUMN IF NOT EXISTS risk_cleared_at TIMESTAMPTZ;
