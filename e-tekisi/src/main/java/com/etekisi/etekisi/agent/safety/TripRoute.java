package com.etekisi.etekisi.agent.safety;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Persistent planned route for a trip — see V4__trip_routes.sql.
 *
 * <p>Captured at trip-start time and consulted by {@link PolylineExpectedRoute}
 * during every safety re-evaluation. One row per booking; the unique
 * constraint enforces this at the DB layer.
 *
 * <p>Anti-hallucination posture: if no row is present the safety agent does
 * not invent a path — it falls back to the {@code ALWAYS_ON_PATH} expected
 * route, which means route-deviation simply doesn't fire (rather than
 * firing false alarms against an imagined polyline).
 */
@Entity
@Table(
    name = "trip_routes",
    schema = "agent_schema",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_trip_routes_booking",
        columnNames = "booking_id"
    ),
    indexes = @Index(name = "idx_trip_routes_booking", columnList = "booking_id")
)
@Getter
@Setter
public class TripRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /** JSON array of [[lat,lng],…]. */
    @Column(name = "polyline_json", nullable = false, columnDefinition = "TEXT")
    private String polylineJson;

    @Column(name = "point_count", nullable = false)
    private int pointCount;

    @Column(name = "distance_meters")
    private Double distanceMeters;

    @Column(nullable = false, length = 32)
    private String source = "ROUTING_API";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "correlation_id", length = 128)
    private String correlationId;
}
