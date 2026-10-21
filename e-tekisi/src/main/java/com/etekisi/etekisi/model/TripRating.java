package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One person's rating of the other after a completed trip (once per side). */
@Entity
@Table(name = "trip_ratings", schema = "commuter_schema")
@Getter
@Setter
public class TripRating {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rater_role", nullable = false, length = 16)
    private Role raterRole;

    @Column(name = "rater_id", nullable = false)
    private Long raterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ratee_role", nullable = false, length = 16)
    private Role rateeRole;

    @Column(name = "ratee_id", nullable = false)
    private Long rateeId;

    @Column(nullable = false)
    private short stars;

    @Column(length = 300)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
