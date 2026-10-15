-- V10: Payout workflow (PENDING -> APPROVED -> PAID, or REJECTED with a
-- refund) and room for encrypted bank details.

ALTER TABLE driver_schema.payout_requests ADD COLUMN IF NOT EXISTS reference        VARCHAR(100);
ALTER TABLE driver_schema.payout_requests ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(300);
ALTER TABLE driver_schema.payout_requests ADD COLUMN IF NOT EXISTS processed_at     TIMESTAMPTZ;
ALTER TABLE driver_schema.payout_requests ADD COLUMN IF NOT EXISTS processed_by     BIGINT;

-- Encrypted values ("enc:v1:" + base64 of IV and ciphertext) need more room
-- than the plain numbers did.
ALTER TABLE driver_schema.payout_requests ALTER COLUMN account_number TYPE VARCHAR(255);
ALTER TABLE driver_schema.payout_requests ALTER COLUMN account_holder TYPE VARCHAR(255);
ALTER TABLE driver_schema.payout_requests ALTER COLUMN phone_number   TYPE VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_payouts_status_created
    ON driver_schema.payout_requests (status, created_at);
