package com.etekisi.etekisi.agent.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only record of every agent decision.
 * Single table, indexed by agent + decisionId — enables offline evaluation,
 * fallback-rate monitoring, and post-incident review.
 */
@Entity
@Table(
    name = "decision_audit",
    schema = "agent_schema",
    indexes = {
        @Index(name = "idx_decision_audit_agent", columnList = "agent"),
        @Index(name = "idx_decision_audit_correlation", columnList = "correlationId"),
        @Index(name = "idx_decision_audit_computed_at", columnList = "computedAt")
    }
)
@Getter
@Setter
public class DecisionAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64) private String decisionId;
    @Column(nullable = false, length = 64) private String agent;
    @Column(nullable = false, length = 32) private String agentVersion;
    @Column(nullable = false)              private Instant computedAt;
    @Column(nullable = false)              private long latencyMs;
    @Column(nullable = false)              private double confidence;
    @Column(nullable = false, length = 16) private String confidenceLevel;
    @Column(nullable = false)              private boolean fallbackUsed;
    @Column(nullable = false)              private boolean degraded;

    @Column(length = 128) private String correlationId;

    /** Comma-separated reason codes (closed vocabulary in dto.ReasonCode). */
    @Column(length = 512) private String reasonCodes;
    /** Comma-separated source labels. */
    @Column(length = 512) private String sourcesConsulted;

    /** Compact, PII-scrubbed summary of the inputs. */
    @Column(length = 1024) private String inputSummary;
    /** Compact JSON of the published output. */
    @Column(columnDefinition = "TEXT") private String outputJson;
}
