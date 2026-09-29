-- V7: Core commuter / driver tables under Flyway.
--
-- Until now these tables only existed because Hibernate ddl-auto=update
-- created them, so a fresh production database (ddl-auto=validate) could not
-- start. Everything here is CREATE ... IF NOT EXISTS: on a legacy database the
-- existing tables are left alone and V8 brings their column types in line.
--
-- Column names follow Hibernate's naming (camelCase -> snake_case).
-- Money is numeric(12,2); Instant fields are TIMESTAMPTZ; LocalDateTime
-- fields are TIMESTAMP.

CREATE SCHEMA IF NOT EXISTS commuter_schema;
CREATE SCHEMA IF NOT EXISTS driver_schema;
CREATE SCHEMA IF NOT EXISTS agent_schema;

-- ---------------------------------------------------------------- commuter --

CREATE TABLE IF NOT EXISTS commuter_schema.users (
    id              BIGSERIAL PRIMARY KEY,
    full_name       VARCHAR(120) NOT NULL,
    email           VARCHAR(200) NOT NULL,
    phone_number    VARCHAR(20)  NOT NULL,
    password        VARCHAR(200) NOT NULL
);

CREATE TABLE IF NOT EXISTS commuter_schema.bookings (
    id              BIGSERIAL PRIMARY KEY,
    commuter_id     BIGINT       NOT NULL,
    driver_id       BIGINT,
    pickup_location VARCHAR(200) NOT NULL,
    destination     VARCHAR(200) NOT NULL,
    scheduled_time  VARCHAR(50),
    fare            NUMERIC(12,2),
    tip             NUMERIC(12,2),
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING'
);

CREATE TABLE IF NOT EXISTS commuter_schema.wallets (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    role            VARCHAR(16)   NOT NULL DEFAULT 'COMMUTER',
    balance         NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_trips     INTEGER       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS commuter_schema.wallet_transactions (
    id                BIGSERIAL PRIMARY KEY,
    owner_id          BIGINT        NOT NULL,
    owner_role        VARCHAR(16)   NOT NULL,
    type              VARCHAR(16)   NOT NULL,
    amount            NUMERIC(12,2) NOT NULL,
    balance_after     NUMERIC(12,2) NOT NULL,
    booking_id        BIGINT,
    payout_request_id BIGINT,
    reference         VARCHAR(100),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS commuter_schema.notifications (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    role            VARCHAR(16)  NOT NULL DEFAULT 'COMMUTER',
    message         VARCHAR(500) NOT NULL,
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS commuter_schema.messages (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      BIGINT        NOT NULL,
    sender_id       BIGINT        NOT NULL,
    sender_role     VARCHAR(16),
    content         VARCHAR(1000) NOT NULL,
    "timestamp"     TIMESTAMP(6)  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS commuter_schema.routes (
    id              BIGSERIAL PRIMARY KEY,
    start_location  VARCHAR(255),
    destination     VARCHAR(255),
    suggested_rank  VARCHAR(255),
    price           VARCHAR(255),
    estimated_time  VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS commuter_schema.saved_ranks (
    id              BIGSERIAL PRIMARY KEY,
    commuter_id     BIGINT,
    name            VARCHAR(255),
    address         VARCHAR(255),
    created_at      TIMESTAMPTZ
);

-- ------------------------------------------------------------------ driver --

CREATE TABLE IF NOT EXISTS driver_schema.drivers (
    id              BIGSERIAL PRIMARY KEY,
    balance         NUMERIC(12,2) NOT NULL DEFAULT 0,
    trips_completed INTEGER       NOT NULL DEFAULT 0,
    full_name       VARCHAR(120)  NOT NULL,
    taxi_plate      VARCHAR(15)   NOT NULL,
    phone_number    VARCHAR(20)   NOT NULL,
    license_number  VARCHAR(50)   NOT NULL,
    email           VARCHAR(200)  NOT NULL,
    password        VARCHAR(200)  NOT NULL,
    is_active       BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS driver_schema.booking_declines (
    id              BIGSERIAL PRIMARY KEY,
    booking_id      BIGINT,
    driver_id       BIGINT,
    created_at      TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS driver_schema.driver_locations (
    driver_id       BIGINT PRIMARY KEY,
    lat             DOUBLE PRECISION,
    lng             DOUBLE PRECISION,
    last_updated    TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS driver_schema.queue_tickets (
    id              BIGSERIAL PRIMARY KEY,
    driver_id       BIGINT,
    driver_name     VARCHAR(255),
    taxi_rank       VARCHAR(255),
    status          VARCHAR(255),
    skip_count      INTEGER NOT NULL DEFAULT 0,
    joined_at       TIMESTAMP(6),
    served_at       TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS driver_schema.payout_requests (
    id              BIGSERIAL PRIMARY KEY,
    driver_id       BIGINT        NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    method          VARCHAR(32)   NOT NULL,
    bank_name       VARCHAR(255),
    account_number  VARCHAR(255),
    account_holder  VARCHAR(255),
    phone_number    VARCHAR(255),
    status          VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS driver_schema.taxi_ranks (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255),
    location        VARCHAR(255),
    status          VARCHAR(255)
);

-- ----------------------------------------------------------- agent audit --
-- DecisionAudit was also only ever created by ddl-auto (V1 comments said so,
-- but no migration created it). AuditRetentionJob deletes from it.

CREATE TABLE IF NOT EXISTS agent_schema.decision_audit (
    id                  BIGSERIAL PRIMARY KEY,
    decision_id         VARCHAR(64)   NOT NULL,
    agent               VARCHAR(64)   NOT NULL,
    agent_version       VARCHAR(32)   NOT NULL,
    computed_at         TIMESTAMPTZ   NOT NULL,
    latency_ms          BIGINT        NOT NULL,
    confidence          DOUBLE PRECISION NOT NULL,
    confidence_level    VARCHAR(16)   NOT NULL,
    fallback_used       BOOLEAN       NOT NULL,
    degraded            BOOLEAN       NOT NULL,
    correlation_id      VARCHAR(128),
    reason_codes        VARCHAR(512),
    sources_consulted   VARCHAR(512),
    input_summary       VARCHAR(1024),
    output_json         TEXT
);

CREATE INDEX IF NOT EXISTS idx_decision_audit_agent       ON agent_schema.decision_audit (agent);
CREATE INDEX IF NOT EXISTS idx_decision_audit_correlation ON agent_schema.decision_audit (correlation_id);
CREATE INDEX IF NOT EXISTS idx_decision_audit_computed_at ON agent_schema.decision_audit (computed_at);

-- ------------------------------------------------------------------- staff --

CREATE TABLE IF NOT EXISTS agent_schema.staff_users (
    id              BIGSERIAL PRIMARY KEY,
    full_name       VARCHAR(120) NOT NULL,
    email           VARCHAR(200) NOT NULL,
    password        VARCHAR(200) NOT NULL,
    role            VARCHAR(16)  NOT NULL DEFAULT 'OPERATOR'
);

-- ----------------------------------------------------------------- indexes --

CREATE INDEX IF NOT EXISTS idx_bookings_status      ON commuter_schema.bookings (status);
CREATE INDEX IF NOT EXISTS idx_bookings_commuter    ON commuter_schema.bookings (commuter_id);
CREATE INDEX IF NOT EXISTS idx_bookings_driver      ON commuter_schema.bookings (driver_id);
CREATE INDEX IF NOT EXISTS idx_notifications_owner  ON commuter_schema.notifications (user_id, role, is_read);
CREATE INDEX IF NOT EXISTS idx_messages_booking     ON commuter_schema.messages (booking_id);
CREATE INDEX IF NOT EXISTS idx_wallet_tx_owner_created
    ON commuter_schema.wallet_transactions (owner_id, owner_role, created_at);
CREATE INDEX IF NOT EXISTS idx_wallet_tx_booking    ON commuter_schema.wallet_transactions (booking_id);
CREATE INDEX IF NOT EXISTS idx_booking_declines_driver_created
    ON driver_schema.booking_declines (driver_id, created_at);
CREATE INDEX IF NOT EXISTS idx_queue_rank_status    ON driver_schema.queue_tickets (taxi_rank, status);
CREATE INDEX IF NOT EXISTS idx_payouts_driver_created
    ON driver_schema.payout_requests (driver_id, created_at);
CREATE INDEX IF NOT EXISTS idx_driver_locations_updated
    ON driver_schema.driver_locations (last_updated);

-- Unique rules. On a legacy database that already holds duplicates these
-- can't be created; that is reported as a WARNING instead of failing the
-- migration, so the duplicates can be cleaned up by hand first.
DO $$
BEGIN
    CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower   ON commuter_schema.users (lower(email));
EXCEPTION WHEN unique_violation THEN
    RAISE WARNING 'uq_users_email_lower not created: duplicate commuter emails exist';
END $$;

DO $$
BEGIN
    CREATE UNIQUE INDEX IF NOT EXISTS uq_drivers_email_lower ON driver_schema.drivers (lower(email));
EXCEPTION WHEN unique_violation THEN
    RAISE WARNING 'uq_drivers_email_lower not created: duplicate driver emails exist';
END $$;

DO $$
BEGIN
    CREATE UNIQUE INDEX IF NOT EXISTS uq_wallets_user_role   ON commuter_schema.wallets (user_id, role);
EXCEPTION WHEN unique_violation THEN
    RAISE WARNING 'uq_wallets_user_role not created: a user has more than one wallet';
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_staff_users_email_lower ON agent_schema.staff_users (lower(email));
