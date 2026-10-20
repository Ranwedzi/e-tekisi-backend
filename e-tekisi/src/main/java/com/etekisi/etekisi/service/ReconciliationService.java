package com.etekisi.etekisi.service;

import com.etekisi.etekisi.model.ReconciliationFinding;
import com.etekisi.etekisi.model.ReconciliationFinding.Severity;
import com.etekisi.etekisi.model.ReconciliationRun;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.ReconciliationFindingRepository;
import com.etekisi.etekisi.repository.ReconciliationRunRepository;
import com.etekisi.etekisi.web.ApiException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Daily check that every rand is accounted for. All reads happen in one
 * REPEATABLE READ transaction, so the checks see a single consistent
 * snapshot even while trips and payouts are happening.
 *
 * <ol>
 *   <li><b>Ledger chain</b>: each entry's balance_after = the previous
 *       balance_after + its amount. A first entry that starts above zero is a
 *       balance from before the ledger existed (WARNING); below zero is an
 *       error.</li>
 *   <li><b>Balances</b>: each commuter wallet and driver balance equals the
 *       balance_after of its latest ledger entry (zero if it has none).</li>
 *   <li><b>Trips</b>: the money matches the status. Accepted: charged once.
 *       Completed: charged and the driver credited the same amount.
 *       Cancelled: anything charged was refunded. Pending / offered: nothing.</li>
 *   <li><b>Payouts</b>: the amount left the driver's balance, and came back
 *       only if the payout was rejected.</li>
 *   <li><b>Orphans</b>: ledger entries pointing at trips or payouts that don't
 *       exist, or missing the reference their type needs.</li>
 *   <li><b>Conservation</b>: top-ups + opening balances = wallet balances +
 *       driver balances + fares in flight + payouts.</li>
 * </ol>
 *
 * Findings are stored with the run; {@code tekisi_reconciliation_*} gauges
 * let monitoring page on discrepancies or on the check not running.
 * Settlement against the payment provider is added with the provider.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int MAX_STORED_FINDINGS = 500;
    private static final String TX = "commuter_schema.wallet_transactions";

    public record Report(ReconciliationRun run, List<ReconciliationFinding> findings) {}

    private final JdbcTemplate jdbc;
    private final ReconciliationRunRepository runs;
    private final ReconciliationFindingRepository findings;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate write;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastCritical = new AtomicLong(0);
    private final AtomicLong lastWarnings = new AtomicLong(0);
    private final AtomicLong lastSuccessEpoch = new AtomicLong(0);
    private final AtomicLong lastFailed = new AtomicLong(0);

    public ReconciliationService(JdbcTemplate jdbc, ReconciliationRunRepository runs,
                                 ReconciliationFindingRepository findings, PlatformTransactionManager txManager,
                                 ObjectProvider<MeterRegistry> registry) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.findings = findings;
        this.snapshot = new TransactionTemplate(txManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.write = new TransactionTemplate(txManager);
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        MeterRegistry r = registry.getIfAvailable();
        if (r != null) {
            Gauge.builder("tekisi.reconciliation.critical", lastCritical, AtomicLong::get)
                    .description("CRITICAL findings in the latest reconciliation run").register(r);
            Gauge.builder("tekisi.reconciliation.warnings", lastWarnings, AtomicLong::get)
                    .description("WARNING findings in the latest reconciliation run").register(r);
            Gauge.builder("tekisi.reconciliation.last_success_seconds", lastSuccessEpoch, AtomicLong::get)
                    .description("When a reconciliation run last completed (epoch seconds)").register(r);
            Gauge.builder("tekisi.reconciliation.failed", lastFailed, AtomicLong::get)
                    .description("1 if the latest reconciliation run errored").register(r);
        }
        try {
            runs.findFirstByStatusNotOrderByIdDesc(ReconciliationRun.Status.RUNNING).ifPresent(this::publish);
        } catch (RuntimeException e) {
            log.debug("reconciliation.gauges_not_primed error={}", e.getMessage());
        }
    }

    @SchedulerLock(name = "reconciliation", lockAtMostFor = "PT2H")
    @Scheduled(cron = "${tekisi.reconciliation.cron:0 30 4 * * *}", zone = "Africa/Johannesburg")
    public void scheduled() {
        try {
            run("SCHEDULED");
        } catch (RuntimeException e) {
            log.error("reconciliation.scheduled_failed error={}", e.getMessage());
        }
    }

    /** Runs all checks now. 409 if a run is already in progress. */
    public Report run(String triggeredBy) {
        if (!running.compareAndSet(false, true)) {
            throw ApiException.conflict("RECONCILIATION_RUNNING", "A reconciliation run is already in progress.");
        }
        try {
            ReconciliationRun run = write.execute(s -> {
                ReconciliationRun r = new ReconciliationRun();
                r.setStartedAt(Instant.now());
                r.setTriggeredBy(triggeredBy);
                return runs.save(r);
            });
            List<ReconciliationFinding> found = new ArrayList<>();
            try {
                snapshot.executeWithoutResult(s -> check(run, found));
                run.setCriticalCount((int) found.stream().filter(f -> f.getSeverity() == Severity.CRITICAL).count());
                run.setWarningCount(found.size() - run.getCriticalCount());
                run.setStatus(run.getCriticalCount() > 0 ? ReconciliationRun.Status.DISCREPANCIES
                        : run.getWarningCount() > 0 ? ReconciliationRun.Status.WARNINGS : ReconciliationRun.Status.OK);
            } catch (RuntimeException e) {
                run.setStatus(ReconciliationRun.Status.FAILED);
                String msg = String.valueOf(e.getMessage());
                run.setError(msg.length() > 500 ? msg.substring(0, 500) : msg);
                log.error("reconciliation.failed runId={} error={}", run.getId(), msg);
            }
            run.setFinishedAt(Instant.now());
            List<ReconciliationFinding> stored = found.stream()
                    .sorted(Comparator.comparing(ReconciliationFinding::getSeverity))
                    .limit(MAX_STORED_FINDINGS).toList();
            stored.forEach(f -> f.setRunId(run.getId()));
            write.executeWithoutResult(s -> {
                runs.save(run);
                findings.saveAll(stored);
            });
            publish(run);
            if (run.getStatus() == ReconciliationRun.Status.DISCREPANCIES) {
                log.error("reconciliation.discrepancies runId={} critical={} warnings={}",
                        run.getId(), run.getCriticalCount(), run.getWarningCount());
            } else {
                log.info("reconciliation.done runId={} status={} warnings={} ledgerRows={}",
                        run.getId(), run.getStatus(), run.getWarningCount(), run.getLedgerRows());
            }
            return new Report(run, stored);
        } finally {
            running.set(false);
        }
    }

    public Optional<Report> latest() {
        return runs.findFirstByStatusNotOrderByIdDesc(ReconciliationRun.Status.RUNNING)
                .map(r -> new Report(r, findings.findTop500ByRunIdOrderBySeverityAscIdAsc(r.getId())));
    }

    public List<ReconciliationRun> recent() {
        return runs.findTop30ByOrderByIdDesc();
    }

    @SchedulerLock(name = "reconciliation-purge", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${tekisi.reconciliation.purge-cron:0 40 4 * * *}")
    public void purgeOld() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(400)); // financial year plus a margin
        write.executeWithoutResult(s -> {
            findings.deleteForRunsStartedBefore(cutoff);
            runs.deleteStartedBefore(cutoff);
        });
    }

    private void publish(ReconciliationRun r) {
        lastCritical.set(r.getCriticalCount());
        lastWarnings.set(r.getWarningCount());
        lastFailed.set(r.getStatus() == ReconciliationRun.Status.FAILED ? 1 : 0);
        if (r.getStatus() != ReconciliationRun.Status.FAILED && r.getFinishedAt() != null) {
            lastSuccessEpoch.set(r.getFinishedAt().getEpochSecond());
        }
    }

    // ---------------------------------------------------------------- checks --

    private void check(ReconciliationRun run, List<ReconciliationFinding> out) {
        run.setLedgerRows(Objects.requireNonNullElse(jdbc.queryForObject("SELECT COUNT(*) FROM " + TX, Long.class), 0L));
        BigDecimal opening = checkLedgerChain(out);
        checkBalances(out);
        BigDecimal inFlight = checkTrips(out);
        checkPayouts(out);
        checkOrphans(out);

        BigDecimal topups = sum("SELECT SUM(amount) FROM " + TX + " WHERE type = 'TOPUP'");
        BigDecimal wallets = sum("SELECT SUM(balance) FROM commuter_schema.wallets");
        BigDecimal drivers = sum("SELECT SUM(balance) FROM driver_schema.drivers");
        // PAYOUT rows are negative and reversals positive: their negated sum is what left driver balances for good.
        BigDecimal paidOut = sum("SELECT SUM(amount) FROM " + TX + " WHERE type IN ('PAYOUT', 'PAYOUT_REVERSAL')").negate();
        run.setTopups(topups);
        run.setOpeningBalances(opening);
        run.setWalletBalances(wallets);
        run.setDriverBalances(drivers);
        run.setInFlight(inFlight);
        run.setPaidOut(paidOut);

        BigDecimal in = topups.add(opening);
        BigDecimal accounted = wallets.add(drivers).add(inFlight).add(paidOut);
        if (in.compareTo(accounted) != 0) {
            out.add(finding(Severity.CRITICAL, "CONSERVATION_MISMATCH", null, null, in, accounted,
                    "Money in (top-ups " + topups + " + opening balances " + opening + ") doesn't equal money accounted for (wallets "
                            + wallets + " + drivers " + drivers + " + in-flight fares " + inFlight + " + payouts " + paidOut + ")"));
        }
    }

    /** Returns the sum of opening balances (first entries that didn't start from zero). */
    private BigDecimal checkLedgerChain(List<ReconciliationFinding> out) {
        BigDecimal[] opening = {BigDecimal.ZERO};
        jdbc.query("""
                SELECT id, owner_id, owner_role, amount, balance_after, prev FROM (
                  SELECT id, owner_id, owner_role, amount, balance_after,
                         LAG(balance_after) OVER (PARTITION BY owner_id, owner_role ORDER BY id) AS prev
                  FROM commuter_schema.wallet_transactions) t
                WHERE balance_after <> COALESCE(prev, 0) + amount
                """, rs -> {
            BigDecimal prev = rs.getBigDecimal("prev");
            BigDecimal amount = rs.getBigDecimal("amount");
            BigDecimal after = rs.getBigDecimal("balance_after");
            ReconciliationFinding f;
            BigDecimal start = after.subtract(amount);
            if (prev == null && start.signum() > 0) {
                opening[0] = opening[0].add(start);
                f = finding(Severity.WARNING, "OPENING_BALANCE", null, null, BigDecimal.ZERO, start,
                        "The first ledger entry starts from " + start + ", not 0: a balance from before the ledger existed.");
            } else {
                // A first entry implying a negative opening balance can't be legitimate.
                BigDecimal before = prev == null ? BigDecimal.ZERO : prev;
                f = finding(Severity.CRITICAL, "LEDGER_CHAIN_BROKEN", null, null, before.add(amount), after,
                        "Entry " + rs.getLong("id") + ": previous balance " + before + " + amount " + amount + " should give "
                                + before.add(amount) + ", but it records " + after + ".");
            }
            f.setLedgerId(rs.getLong("id"));
            f.setOwnerId(rs.getLong("owner_id"));
            f.setOwnerRole(Role.valueOf(rs.getString("owner_role")));
            out.add(f);
        });
        return opening[0];
    }

    private void checkBalances(List<ReconciliationFinding> out) {
        Map<String, BigDecimal> last = new HashMap<>();
        jdbc.query("""
                SELECT owner_id, owner_role, balance_after FROM (
                  SELECT owner_id, owner_role, balance_after,
                         ROW_NUMBER() OVER (PARTITION BY owner_id, owner_role ORDER BY id DESC) AS rn
                  FROM commuter_schema.wallet_transactions) t
                WHERE rn = 1
                """, rs -> {
            last.put(rs.getString("owner_role") + ":" + rs.getLong("owner_id"), rs.getBigDecimal("balance_after"));
        });
        jdbc.query("SELECT user_id, balance FROM commuter_schema.wallets WHERE role = 'COMMUTER'", rs -> {
            compareBalance(out, Role.COMMUTER, rs.getLong("user_id"), rs.getBigDecimal("balance"), last);
        });
        jdbc.query("SELECT id, balance FROM driver_schema.drivers", rs -> {
            compareBalance(out, Role.DRIVER, rs.getLong("id"), rs.getBigDecimal("balance"), last);
        });
    }

    private void compareBalance(List<ReconciliationFinding> out, Role role, long id, BigDecimal actual,
                                Map<String, BigDecimal> last) {
        BigDecimal expected = last.getOrDefault(role + ":" + id, BigDecimal.ZERO);
        if (actual == null) actual = BigDecimal.ZERO;
        if (expected.compareTo(actual) != 0) {
            ReconciliationFinding f = finding(Severity.CRITICAL, "BALANCE_MISMATCH", null, null, expected, actual,
                    (role == Role.COMMUTER ? "Wallet" : "Driver balance") + " is " + actual
                            + " but the ledger says " + expected + ".");
            f.setOwnerRole(role);
            f.setOwnerId(id);
            out.add(f);
        }
    }

    /** Returns the fares of accepted trips (charged, not yet earned). */
    private BigDecimal checkTrips(List<ReconciliationFinding> out) {
        BigDecimal[] inFlight = {BigDecimal.ZERO};
        jdbc.query("""
                SELECT b.id, b.status, COALESCE(b.fare, 0) + COALESCE(b.tip, 0) AS total,
                       COALESCE(SUM(CASE WHEN t.type = 'FARE_DEBIT' THEN -t.amount END), 0) AS charged,
                       COALESCE(SUM(CASE WHEN t.type = 'FARE_REFUND' THEN t.amount END), 0) AS refunded,
                       COALESCE(SUM(CASE WHEN t.type = 'TRIP_EARNING' THEN t.amount END), 0) AS earned,
                       COUNT(CASE WHEN t.type = 'FARE_DEBIT' THEN 1 END) AS charges
                FROM commuter_schema.bookings b
                LEFT JOIN commuter_schema.wallet_transactions t ON t.booking_id = b.id
                GROUP BY b.id, b.status, b.fare, b.tip
                """, rs -> {
            long id = rs.getLong("id");
            String status = rs.getString("status");
            BigDecimal total = rs.getBigDecimal("total");
            BigDecimal charged = rs.getBigDecimal("charged");
            BigDecimal refunded = rs.getBigDecimal("refunded");
            BigDecimal earned = rs.getBigDecimal("earned");
            long charges = rs.getLong("charges");
            String problem = null;
            BigDecimal expected = null, actual = null;
            switch (status) {
                case "ACCEPTED" -> {
                    inFlight[0] = inFlight[0].add(charged.subtract(refunded));
                    if (charges != 1 || charged.compareTo(total) != 0) {
                        problem = "Accepted trip should be charged " + total + " once; charged " + charged + " in " + charges + " entries.";
                        expected = total; actual = charged;
                    } else if (refunded.signum() != 0 || earned.signum() != 0) {
                        problem = "Accepted trip already has a refund (" + refunded + ") or driver earning (" + earned + ").";
                    }
                }
                case "COMPLETED" -> {
                    if (charges != 1 || charged.compareTo(total) != 0 || earned.compareTo(total) != 0 || refunded.signum() != 0) {
                        problem = "Completed trip of " + total + ": charged " + charged + " (" + charges + "x), driver earned "
                                + earned + ", refunded " + refunded + ". Expected charged = earned = " + total + ", no refund.";
                        expected = total; actual = earned;
                    }
                }
                case "CANCELLED" -> {
                    if (charged.compareTo(refunded) != 0 || earned.signum() != 0) {
                        problem = "Cancelled trip: charged " + charged + " but refunded " + refunded
                                + (earned.signum() != 0 ? " and the driver earned " + earned : "") + ".";
                        expected = charged; actual = refunded;
                    }
                }
                default -> { // PENDING, OFFERED: no money moves until the commuter accepts
                    if (charged.signum() != 0 || refunded.signum() != 0 || earned.signum() != 0) {
                        problem = status + " trip already has money movements (charged " + charged + ", refunded "
                                + refunded + ", earned " + earned + ").";
                    }
                }
            }
            if (problem != null) {
                ReconciliationFinding f = finding(Severity.CRITICAL, "TRIP_MONEY_MISMATCH", id, null, expected, actual, problem);
                out.add(f);
            }
        });
        return inFlight[0];
    }

    private void checkPayouts(List<ReconciliationFinding> out) {
        jdbc.query("""
                SELECT p.id, p.driver_id, p.amount, p.status,
                       COALESCE(SUM(CASE WHEN t.type = 'PAYOUT' THEN -t.amount END), 0) AS debited,
                       COALESCE(SUM(CASE WHEN t.type = 'PAYOUT_REVERSAL' THEN t.amount END), 0) AS reversed
                FROM driver_schema.payout_requests p
                LEFT JOIN commuter_schema.wallet_transactions t ON t.payout_request_id = p.id
                GROUP BY p.id, p.driver_id, p.amount, p.status
                """, rs -> {
            BigDecimal amount = rs.getBigDecimal("amount");
            BigDecimal debited = rs.getBigDecimal("debited");
            BigDecimal reversed = rs.getBigDecimal("reversed");
            boolean rejected = "REJECTED".equals(rs.getString("status"));
            String problem = null;
            if (debited.compareTo(amount) != 0) {
                problem = "Payout of " + amount + " took " + debited + " from the driver's balance.";
            } else if (rejected && reversed.compareTo(amount) != 0) {
                problem = "Rejected payout of " + amount + " was only given back " + reversed + ".";
            } else if (!rejected && reversed.signum() != 0) {
                problem = rs.getString("status") + " payout was reversed by " + reversed + ".";
            }
            if (problem != null) {
                ReconciliationFinding f = finding(Severity.CRITICAL, "PAYOUT_MONEY_MISMATCH", null, rs.getLong("id"),
                        amount, rejected ? reversed : debited, problem);
                f.setOwnerRole(Role.DRIVER);
                f.setOwnerId(rs.getLong("driver_id"));
                out.add(f);
            }
        });
    }

    private void checkOrphans(List<ReconciliationFinding> out) {
        jdbc.query("""
                SELECT t.id, t.owner_id, t.owner_role, t.type, t.amount, t.booking_id, t.payout_request_id
                FROM commuter_schema.wallet_transactions t
                LEFT JOIN commuter_schema.bookings b ON b.id = t.booking_id
                LEFT JOIN driver_schema.payout_requests p ON p.id = t.payout_request_id
                WHERE (t.type IN ('FARE_DEBIT', 'FARE_REFUND', 'TRIP_EARNING') AND (t.booking_id IS NULL OR b.id IS NULL))
                   OR (t.type IN ('PAYOUT', 'PAYOUT_REVERSAL') AND (t.payout_request_id IS NULL OR p.id IS NULL))
                """, rs -> {
            ReconciliationFinding f = finding(Severity.CRITICAL, "ORPHAN_LEDGER_ENTRY", null, null, null, rs.getBigDecimal("amount"),
                    rs.getString("type") + " entry " + rs.getLong("id") + " doesn't point at an existing "
                            + (rs.getString("type").startsWith("PAYOUT") ? "payout." : "trip."));
            f.setLedgerId(rs.getLong("id"));
            f.setOwnerId(rs.getLong("owner_id"));
            f.setOwnerRole(Role.valueOf(rs.getString("owner_role")));
            out.add(f);
        });
    }

    private BigDecimal sum(String sql) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class);
        return v == null ? BigDecimal.ZERO : Money.of(v);
    }

    private static ReconciliationFinding finding(Severity severity, String code, Long bookingId, Long payoutId,
                                                 BigDecimal expected, BigDecimal actual, String detail) {
        ReconciliationFinding f = new ReconciliationFinding();
        f.setSeverity(severity);
        f.setCode(code);
        f.setBookingId(bookingId);
        f.setPayoutId(payoutId);
        f.setExpected(expected);
        f.setActual(actual);
        f.setDetail(detail.length() > 500 ? detail.substring(0, 500) : detail);
        return f;
    }
}
