package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Append-only ledger. Every change to a commuter wallet balance or a driver
 * balance writes exactly one row, with a signed amount and the balance after.
 */
@Entity
@Table(name = "wallet_transactions", schema = "commuter_schema",
       indexes = @Index(name = "idx_wallet_tx_owner_created", columnList = "owner_id, owner_role, created_at"))
@Data
public class WalletTransaction {

    public enum Type { TOPUP, FARE_DEBIT, FARE_REFUND, TRIP_EARNING, PAYOUT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_role", nullable = false, length = 16)
    private Role ownerRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    /** Positive = credit, negative = debit. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    private Long bookingId;
    private Long payoutRequestId;

    @Column(length = 100)
    private String reference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
