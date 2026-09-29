-- V6: Per-rank per-minute demand pulse (Phase 3.2).
--
-- A scheduled aggregator writes one row per (rank_name, minute_bucket) every
-- ~60 s. HotspotService reads the trailing 15 minutes to compute a true
-- recent-demand signal instead of the previous "recent15m == pending"
-- shortcut.
--
-- bucket_minute is truncated to the minute (UTC). The unique index makes
-- the writer idempotent — rerunning the aggregator within the same minute
-- updates the row instead of creating a duplicate.

CREATE SCHEMA IF NOT EXISTS agent_schema;

CREATE TABLE IF NOT EXISTS agent_schema.demand_pulse (
    id              BIGSERIAL PRIMARY KEY,
    rank_name       VARCHAR(200) NOT NULL,
    bucket_minute   TIMESTAMPTZ NOT NULL,
    pending_count   INT NOT NULL DEFAULT 0,
    queue_depth     INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_demand_pulse_rank_bucket UNIQUE (rank_name, bucket_minute)
);

CREATE INDEX IF NOT EXISTS idx_demand_pulse_bucket
    ON agent_schema.demand_pulse (bucket_minute DESC);

CREATE INDEX IF NOT EXISTS idx_demand_pulse_rank_bucket
    ON agent_schema.demand_pulse (rank_name, bucket_minute DESC);
