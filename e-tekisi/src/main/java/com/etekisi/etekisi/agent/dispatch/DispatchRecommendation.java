package com.etekisi.etekisi.agent.dispatch;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Phase 3.3 — record of an autonomous-dispatch attempt. See V5__dispatch_recommendation.sql.
 *
 * <p>Created on every call to {@code POST /api/agents/dispatch/auto}.
 * Status transitions:
 * <ul>
 *   <li>PENDING   — operator confirmation required.</li>
 *   <li>EXECUTED  — agent assigned a driver under autonomous policy.</li>
 *   <li>REJECTED  — policy refused (degraded / low confidence / NO_MATCH).</li>
 *   <li>EXPIRED   — operator did not act within the canary window.</li>
 * </ul>
 */
@Entity
@Table(name = "dispatch_recommendation", schema = "agent_schema",
       indexes = {
           @Index(name = "idx_dispatch_rec_booking", columnList = "booking_id"),
           @Index(name = "idx_dispatch_rec_status_created", columnList = "status,created_at")
       })
@Data
public class DispatchRecommendation {

    public enum Status { PENDING, EXECUTED, REJECTED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "recommended_driver_id")
    private Long recommendedDriverId;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "reason_codes", length = 512)
    private String reasonCodes;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private Status status;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;
}
