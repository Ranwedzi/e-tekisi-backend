package com.etekisi.etekisi.agent.trip;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Authoritative per-trip GPS history. Stored in {@code agent_schema} so
 * the agent layer owns its own write surface — existing booking/driver
 * tables stay untouched.
 *
 * Anti-hallucination contract:
 *   - SafetyAgent reads from this table only (via TrustedDataService).
 *   - If no rows exist for a booking, the agent returns INSUFFICIENT_DATA
 *     instead of inferring location from anything else.
 */
@Entity
@Table(
    name = "trip_gps_pings",
    schema = "agent_schema",
    indexes = {
        @Index(name = "idx_trip_gps_booking_recorded", columnList = "bookingId,recordedAt")
    }
)
@Data
public class TripGpsPing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long bookingId;
    @Column(nullable = false) private double lat;
    @Column(nullable = false) private double lng;
    @Column(nullable = false) private Instant recordedAt;

    /** Where the ping came from — DRIVER_APP, COMMUTER_APP, SYSTEM. Free-text bounded by ingest layer. */
    @Column(length = 32) private String source;
}
