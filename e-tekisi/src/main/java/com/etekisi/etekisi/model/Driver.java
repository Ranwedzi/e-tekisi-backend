package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** API input uses RegisterDriverRequest; API output uses DriverDto. */
@Entity
@Table(name = "drivers", schema = "driver_schema")
@Getter
@Setter
public class Driver implements AccountHolder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer tripsCompleted = 0;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 10)
    private String taxiPlate;

    @Column(nullable = false, length = 20)
    private String phoneNumber;

    @Column(nullable = false, length = 50)
    private String licenseNumber;

    @Column(nullable = false, length = 200, unique = true)
    private String email;

    /** BCrypt hash. */
    @Column(nullable = false, length = 200)
    private String password;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = false;

    /** Operators check licence, PrDP and permit details before a driver can take trips. */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private DriverApproval approvalStatus = DriverApproval.PENDING_APPROVAL;

    /** Shown to the driver when rejected; free text from the operator. */
    @Column(name = "approval_note", length = 300)
    private String approvalNote;

    @Column(name = "approval_decided_at")
    private Instant approvalDecidedAt;

    @Column(name = "approval_decided_by")
    private Long approvalDecidedBy;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

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

    public boolean isApproved() {
        return approvalStatus == DriverApproval.APPROVED;
    }
}
