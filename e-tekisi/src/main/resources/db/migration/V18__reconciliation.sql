-- V18: Daily money reconciliation. Each run checks the ledger against wallet
-- and driver balances, trips and payouts, and records what didn't add up.

CREATE TABLE IF NOT EXISTS commuter_schema.reconciliation_runs (
    id                 BIGSERIAL PRIMARY KEY,
    started_at         TIMESTAMPTZ   NOT NULL,
    finished_at        TIMESTAMPTZ,
    status             VARCHAR(16)   NOT NULL,
    triggered_by       VARCHAR(32)   NOT NULL,
    critical_count     INT           NOT NULL DEFAULT 0,
    warning_count      INT           NOT NULL DEFAULT 0,
    ledger_rows        BIGINT        NOT NULL DEFAULT 0,
    topups             NUMERIC(14,2) NOT NULL DEFAULT 0,
    opening_balances   NUMERIC(14,2) NOT NULL DEFAULT 0,
    wallet_balances    NUMERIC(14,2) NOT NULL DEFAULT 0,
    driver_balances    NUMERIC(14,2) NOT NULL DEFAULT 0,
    in_flight          NUMERIC(14,2) NOT NULL DEFAULT 0,
    paid_out           NUMERIC(14,2) NOT NULL DEFAULT 0,
    error              VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_recon_runs_started ON commuter_schema.reconciliation_runs (started_at);

CREATE TABLE IF NOT EXISTS commuter_schema.reconciliation_findings (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT        NOT NULL REFERENCES commuter_schema.reconciliation_runs (id) ON DELETE CASCADE,
    severity     VARCHAR(16)   NOT NULL,
    code         VARCHAR(40)   NOT NULL,
    owner_role   VARCHAR(16),
    owner_id     BIGINT,
    booking_id   BIGINT,
    payout_id    BIGINT,
    ledger_id    BIGINT,
    expected     NUMERIC(14,2),
    actual       NUMERIC(14,2),
    detail       VARCHAR(500)  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_recon_findings_run ON commuter_schema.reconciliation_findings (run_id);
