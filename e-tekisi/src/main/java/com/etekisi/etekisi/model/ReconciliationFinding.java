package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/** Something that didn't add up in a {@link ReconciliationRun}. */
@Entity
@Table(name = "reconciliation_findings", schema = "commuter_schema")
@Data
public class ReconciliationFinding {

    public enum Severity { CRITICAL, WARNING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    /** LEDGER_CHAIN_BROKEN, BALANCE_MISMATCH, TRIP_MONEY_MISMATCH, PAYOUT_MONEY_MISMATCH, ORPHAN_LEDGER_ENTRY, CONSERVATION_MISMATCH, OPENING_BALANCE. */
    @Column(nullable = false, length = 40)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_role", length = 16)
    private Role ownerRole;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "payout_id")
    private Long payoutId;

    @Column(name = "ledger_id")
    private Long ledgerId;

    @Column(precision = 14, scale = 2)
    private BigDecimal expected;

    @Column(precision = 14, scale = 2)
    private BigDecimal actual;

    @Column(nullable = false, length = 500)
    private String detail;
}
