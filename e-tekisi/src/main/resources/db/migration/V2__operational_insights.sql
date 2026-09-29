-- ============================================================================
-- Operational insights produced by the operator-side intelligence layer.
--
-- These are bounded, auditable records — never free-form LLM output.
-- TripAnomalyAgent and OperationalInsightListener write here; the operator
-- console reads from here.
--
-- Anti-hallucination posture:
--   - 'code' values come from a closed enum (InsightCode); no free text.
--   - 'message' is bounded (<= 512 chars) and built from trusted facts only.
--   - 'severity' is a closed enum (INFO/WARN/CRITICAL).
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent_schema.operational_insights (
    id              BIGSERIAL    PRIMARY KEY,
    booking_id      BIGINT,
    driver_id       BIGINT,
    commuter_id     BIGINT,
    type            VARCHAR(32)  NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    severity        VARCHAR(16)  NOT NULL,
    message         VARCHAR(512) NOT NULL,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_op_insights_created
    ON agent_schema.operational_insights (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_op_insights_booking
    ON agent_schema.operational_insights (booking_id);
CREATE INDEX IF NOT EXISTS idx_op_insights_severity_created
    ON agent_schema.operational_insights (severity, created_at DESC);
