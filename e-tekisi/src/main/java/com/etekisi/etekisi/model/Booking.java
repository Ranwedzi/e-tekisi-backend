package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/** API input uses CreateBookingRequest; API output uses BookingDto. */
@Entity
@EntityListeners(com.etekisi.etekisi.realtime.LiveEntityListeners.Bookings.class)
@Table(name = "bookings", schema = "commuter_schema")
@Data
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long commuterId;

    private Long driverId;

    @Column(nullable = false, length = 200)
    private String pickupLocation;

    @Column(nullable = false, length = 200)
    private String destination;

    @Column(length = 50)
    private String scheduledTime;

    @Column(precision = 12, scale = 2)
    private BigDecimal fare;

    @Column(precision = 12, scale = 2)
    private BigDecimal tip;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BookingStatus status = BookingStatus.PENDING;

    /** While OFFERED: when the driver's fare offer lapses and the booking returns to PENDING. */
    @Column(name = "offer_expires_at")
    private Instant offerExpiresAt;

    /** Where the commuter is picked up and going, when known (map pin or geocoded). */
    @Column(name = "pickup_lat")
    private Double pickupLat;
    @Column(name = "pickup_lng")
    private Double pickupLng;
    @Column(name = "destination_lat")
    private Double destinationLat;
    @Column(name = "destination_lng")
    private Double destinationLng;

    /** Trip progress: the driver is at the pick-up, the commuter is on board, the trip is done. */
    @Column(name = "arrived_at")
    private Instant arrivedAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    /** Fare plus tip; zero when neither is set. */
    public BigDecimal total() {
        BigDecimal f = fare != null ? fare : BigDecimal.ZERO;
        BigDecimal t = tip != null ? tip : BigDecimal.ZERO;
        return f.add(t);
    }
}
