package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A commuter account. API input uses RegisterCommuterRequest; API output uses CommuterDto. */
@Entity
@Table(name = "users", schema = "commuter_schema")
@Getter
@Setter
public class User implements AccountHolder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 200, unique = true)
    private String email;

    @Column(nullable = false, length = 20)
    private String phoneNumber;

    /** BCrypt hash. */
    @Column(nullable = false, length = 200)
    private String password;

    /** Set when the account is deleted; personal fields are anonymised at the same time. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    /** Version of the terms and privacy policy the person accepted, and when. */
    @Column(name = "terms_version", length = 40)
    private String termsVersion;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "credentials_changed_at")
    private Instant credentialsChangedAt;

    /** Ratings received: running totals (see TripRating). */
    @Column(name = "rating_sum", nullable = false)
    private int ratingSum = 0;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount = 0;

    /** Average stars to one decimal, or null before the first rating. */
    public Double averageRating() {
        return ratingCount == 0 ? null : Math.round(ratingSum * 10.0 / ratingCount) / 10.0;
    }
}
