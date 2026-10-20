-- V12: Account deletion (POPIA right to deletion). Deleted accounts keep
-- their row, anonymised, so trips and the money ledger still add up; this
-- column records when it happened.

ALTER TABLE commuter_schema.users  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE driver_schema.drivers  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
