package com.etekisi.etekisi.model;

import com.etekisi.etekisi.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A driver's cash-out. The amount leaves their balance when the request is
 * made. Operators then approve it, mark it paid (with the bank/provider
 * reference), or reject it (the amount goes back to the driver).
 *
 * <pre>
 *   PENDING -> APPROVED -> PAID
 *      |          |
 *      +----------+-> REJECTED (refunded)
 * </pre>
 */
@Entity
@Table(name = "payout_requests", schema = "driver_schema")
@Data
public class PayoutRequest {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String PAID = "PAID";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long driverId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 32)
    private String method; // BANK_TRANSFER | FNB_EWALLET | CAPITEC_CASH_SEND

    private String bankName;

    /** Encrypted at rest (AES-GCM, see FieldEncryptor). */
    @Convert(converter = EncryptedStringConverter.class)
    private String accountNumber;

    @Convert(converter = EncryptedStringConverter.class)
    private String accountHolder;

    @Convert(converter = EncryptedStringConverter.class)
    private String phoneNumber;

    @Column(nullable = false, length = 32)
    private String status = PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** Bank / payout-provider reference, set when marked PAID. */
    @Column(length = 100)
    private String reference;

    @Column(length = 300)
    private String rejectionReason;

    private Instant processedAt;

    /** Staff user who last moved it on. */
    private Long processedBy;

    /** Set when the payment-risk check flagged this payout; approving it then needs an explicit confirmation. */
    @Column(name = "risk_hold", nullable = false)
    private boolean riskHold = false;

    @Column(name = "risk_reasons", length = 300)
    private String riskReasons;

    @Column(name = "risk_cleared_by")
    private Long riskClearedBy;

    @Column(name = "risk_cleared_at")
    private Instant riskClearedAt;
}
