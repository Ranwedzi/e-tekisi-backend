package com.etekisi.etekisi.agent.hotspot;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Phase 3.2 — per-rank per-minute snapshot of demand pressure. Written by
 * {@link DemandPulseAggregator} and read by {@link HotspotService} for the
 * trailing 15-minute pressure signal.
 */
@Entity
@Table(name = "demand_pulse", schema = "agent_schema",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_demand_pulse_rank_bucket",
           columnNames = {"rank_name", "bucket_minute"}
       ),
       indexes = {
           @Index(name = "idx_demand_pulse_bucket", columnList = "bucket_minute DESC"),
           @Index(name = "idx_demand_pulse_rank_bucket", columnList = "rank_name,bucket_minute DESC")
       })
@Getter
@Setter
public class DemandPulse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rank_name", nullable = false, length = 200)
    private String rankName;

    @Column(name = "bucket_minute", nullable = false)
    private Instant bucketMinute;

    @Column(name = "pending_count", nullable = false)
    private int pendingCount;

    @Column(name = "queue_depth", nullable = false)
    private int queueDepth;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
