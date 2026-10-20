-- V11: Driver vetting. New drivers start PENDING_APPROVAL and can't go online,
-- make offers or join queues until an operator approves them.
--
-- Drivers that already exist are marked APPROVED so this migration doesn't
-- take working drivers off the road. Operators should still review them:
--   select id, full_name, taxi_plate, license_number from driver_schema.drivers
--   where approval_decided_at is null;

ALTER TABLE driver_schema.drivers ADD COLUMN IF NOT EXISTS approval_status     VARCHAR(20);
ALTER TABLE driver_schema.drivers ADD COLUMN IF NOT EXISTS approval_note       VARCHAR(300);
ALTER TABLE driver_schema.drivers ADD COLUMN IF NOT EXISTS approval_decided_at TIMESTAMPTZ;
ALTER TABLE driver_schema.drivers ADD COLUMN IF NOT EXISTS approval_decided_by BIGINT;
ALTER TABLE driver_schema.drivers ADD COLUMN IF NOT EXISTS registered_at       TIMESTAMPTZ;

UPDATE driver_schema.drivers SET approval_status = 'APPROVED' WHERE approval_status IS NULL;
UPDATE driver_schema.drivers SET registered_at = now() WHERE registered_at IS NULL;

ALTER TABLE driver_schema.drivers ALTER COLUMN approval_status SET NOT NULL;
ALTER TABLE driver_schema.drivers ALTER COLUMN approval_status SET DEFAULT 'PENDING_APPROVAL';
ALTER TABLE driver_schema.drivers ALTER COLUMN registered_at SET NOT NULL;
ALTER TABLE driver_schema.drivers ALTER COLUMN registered_at SET DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_drivers_approval ON driver_schema.drivers (approval_status, registered_at);
