-- V13: An operator acknowledges a safety alert to show someone is handling
-- it. Unacknowledged CRITICAL alerts are exported as a gauge so monitoring
-- can escalate when nobody responds.

ALTER TABLE agent_schema.operational_insights ADD COLUMN IF NOT EXISTS acknowledged_at   TIMESTAMPTZ;
ALTER TABLE agent_schema.operational_insights ADD COLUMN IF NOT EXISTS acknowledged_by   BIGINT;
ALTER TABLE agent_schema.operational_insights ADD COLUMN IF NOT EXISTS acknowledged_note VARCHAR(300);

CREATE INDEX IF NOT EXISTS idx_op_insights_unacked
    ON agent_schema.operational_insights (severity, created_at)
    WHERE acknowledged_at IS NULL;
