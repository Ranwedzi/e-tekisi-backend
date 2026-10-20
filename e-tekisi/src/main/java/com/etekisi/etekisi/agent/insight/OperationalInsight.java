package com.etekisi.etekisi.agent.insight;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Bounded operator-facing insight emitted by {@link TripAnomalyAgent} and
 * {@link OperationalInsightListener}. Operators read these from the ops
 * console; they are NEVER free-form LLM output.
 *
 * Anti-hallucination contract:
 *   - {@code code} is drawn from {@link InsightCode} (closed enum).
 *   - {@code severity} is closed (INFO / WARN / CRITICAL).
 *   - {@code message} is &lt;=512 chars, populated only from trusted facts.
 */
@Entity
@Table(
    name = "operational_insights",
    schema = "agent_schema",
    indexes = {
        @Index(name = "idx_op_insights_created", columnList = "createdAt"),
        @Index(name = "idx_op_insights_booking", columnList = "bookingId"),
        @Index(name = "idx_op_insights_severity_created", columnList = "severity,createdAt")
    }
)
@Data
public class OperationalInsight {

    public enum Type { ANOMALY, METRIC, ALERT }
    public enum Severity { INFO, WARN, CRITICAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long bookingId;
    private Long driverId;
    private Long commuterId;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private Type type;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64)
    private InsightCode code;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(nullable = false, length = 512)
    private String message;

    @Column(length = 64)
    private String correlationId;

    @Column(nullable = false)
    private Instant createdAt;

    /** Set when an operator takes ownership of the alert. */
    private Instant acknowledgedAt;
    private Long acknowledgedBy;
    @Column(length = 300)
    private String acknowledgedNote;
}
