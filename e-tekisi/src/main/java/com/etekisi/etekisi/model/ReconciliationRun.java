package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One reconciliation pass over the money tables. The totals make the
 * conservation check readable:
 * {@code topups + openingBalances = walletBalances + driverBalances + inFlight + paidOut}.
 */
@Entity
@Table(name = "reconciliation_runs", schema = "commuter_schema")
@Getter
@Setter
public class ReconciliationRun {

    /** OK: nothing found. WARNINGS: only explainable oddities. DISCREPANCIES: money doesn't add up. */
    public enum Status { RUNNING, OK, WARNINGS, DISCREPANCIES, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RUNNING;

    /** SCHEDULED or the operator's id as MANUAL:id. */
    @Column(name = "triggered_by", nullable = false, length = 32)
    private String triggeredBy;

    @Column(name = "critical_count", nullable = false)
    private int criticalCount;

    @Column(name = "warning_count", nullable = false)
    private int warningCount;

    @Column(name = "ledger_rows", nullable = false)
    private long ledgerRows;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal topups = BigDecimal.ZERO;

    /** Balances that existed before the ledger did (first entry didn't start from zero). */
    @Column(name = "opening_balances", nullable = false, precision = 14, scale = 2)
    private BigDecimal openingBalances = BigDecimal.ZERO;

    @Column(name = "wallet_balances", nullable = false, precision = 14, scale = 2)
    private BigDecimal walletBalances = BigDecimal.ZERO;

    @Column(name = "driver_balances", nullable = false, precision = 14, scale = 2)
    private BigDecimal driverBalances = BigDecimal.ZERO;

    /** Fares already charged for accepted trips, not yet earned by the driver. */
    @Column(name = "in_flight", nullable = false, precision = 14, scale = 2)
    private BigDecimal inFlight = BigDecimal.ZERO;

    /** Payouts taken from driver balances and not reversed (pending, approved or paid). */
    @Column(name = "paid_out", nullable = false, precision = 14, scale = 2)
    private BigDecimal paidOut = BigDecimal.ZERO;

    @Column(length = 500)
    private String error;
}
