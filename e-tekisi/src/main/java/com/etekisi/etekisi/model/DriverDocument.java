package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** A document a driver uploaded for vetting. The file itself is in {@link DriverDocumentFile}, encrypted. */
@Entity
@EntityListeners(com.etekisi.etekisi.realtime.LiveEntityListeners.Documents.class)
@Table(name = "driver_documents", schema = "driver_schema")
@Getter
@Setter
public class DriverDocument {

    public enum Type { DRIVERS_LICENCE, PRDP, OPERATING_LICENCE, ROADWORTHY, VEHICLE_PHOTO, ID_DOCUMENT, OTHER }

    /** EXPIRED: was accepted, but its expiry date has passed. SUPERSEDED: a newer one of the same type was accepted. */
    public enum Status { SUBMITTED, ACCEPTED, REJECTED, EXPIRED, SUPERSEDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 32)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.SUBMITTED;

    @Column(name = "file_name", nullable = false, length = 120)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 40)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    /** As stated by the driver; the operator checks it against the document. */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "review_note", length = 300)
    private String reviewNote;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;
}
