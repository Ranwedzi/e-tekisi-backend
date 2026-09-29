-- V8: Bring legacy (ddl-auto created) tables in line with the entities, and
-- add the idempotency claim state.
--
-- On a fresh database V7 already created everything with these types, so
-- each statement is a no-op there.

-- ---------------------------------------------------- ids: int -> bigint --
-- init.sql created users/drivers with SERIAL (int4); the entities use Long.
ALTER TABLE commuter_schema.users   ALTER COLUMN id TYPE BIGINT;
ALTER TABLE driver_schema.drivers   ALTER COLUMN id TYPE BIGINT;

-- ------------------------------------------- widen legacy text columns --
-- init.sql sized these smaller than the validation rules allow.
ALTER TABLE commuter_schema.users   ALTER COLUMN full_name    TYPE VARCHAR(120);
ALTER TABLE commuter_schema.users   ALTER COLUMN email        TYPE VARCHAR(200);
ALTER TABLE commuter_schema.users   ALTER COLUMN phone_number TYPE VARCHAR(20);
ALTER TABLE driver_schema.drivers   ALTER COLUMN full_name    TYPE VARCHAR(120);

-- --------------------------------------- money: double -> numeric(12,2) --
ALTER TABLE commuter_schema.bookings
    ALTER COLUMN fare TYPE NUMERIC(12,2) USING round(fare::numeric, 2),
    ALTER COLUMN tip  TYPE NUMERIC(12,2) USING round(tip::numeric, 2);

ALTER TABLE commuter_schema.wallets
    ALTER COLUMN balance TYPE NUMERIC(12,2) USING round(coalesce(balance, 0)::numeric, 2);

ALTER TABLE driver_schema.drivers
    ALTER COLUMN balance TYPE NUMERIC(12,2) USING round(coalesce(balance, 0)::numeric, 2);

ALTER TABLE driver_schema.payout_requests
    ALTER COLUMN amount TYPE NUMERIC(12,2) USING round(amount::numeric, 2);

-- ------------------------------------------- columns added by this change --
ALTER TABLE commuter_schema.messages ADD COLUMN IF NOT EXISTS sender_role VARCHAR(16);

-- ------------------------------------------------ idempotency claim state --
-- Rows are inserted as IN_PROGRESS before the action runs and completed with
-- the response afterwards, so the hash is unknown at insert time.
ALTER TABLE agent_schema.idempotency_keys ADD COLUMN IF NOT EXISTS state VARCHAR(16) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE agent_schema.idempotency_keys ALTER COLUMN response_hash DROP NOT NULL;
