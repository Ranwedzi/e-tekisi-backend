package com.etekisi.etekisi.agent.dispatch;

import com.etekisi.etekisi.agent.audit.DecisionAuditLogger;
import com.etekisi.etekisi.agent.dto.DispatchDecision;
import com.etekisi.etekisi.agent.dto.ReasonCode;
import com.etekisi.etekisi.agent.guardrail.*;
import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.DriverLocation;
import com.etekisi.etekisi.model.QueueTicket;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Deterministic Dispatch & Matching engine.
 *
 * Anti-hallucination posture:
 *   - never assigns a driver without a fresh location ping
 *   - never invents a candidate; only ranks drivers returned by repos
 *   - returns NO_MATCH or DEGRADED_NO_MATCH instead of guessing
 *   - score is a pure function of trusted fields; explainable per-candidate
 *   - PolicyEngine has the final say on whether the assignment is autonomous
 */
@Service
public class DispatchAgent {

    public static final String NAME = "DispatchAgent";
    public static final String VERSION = "1.0.0";

    private final TrustedDataService trusted;
    private final AgentProperties props;
    private final DecisionGate gate;
    private final PolicyEngine policy;
    private final DecisionAuditLogger audit;

    public DispatchAgent(TrustedDataService trusted,
                         AgentProperties props,
                         DecisionGate gate,
                         PolicyEngine policy,
                         DecisionAuditLogger audit) {
        this.trusted = trusted;
        this.props = props;
        this.gate = gate;
        this.policy = policy;
        this.audit = audit;
    }

    public DispatchDecision dispatch(Long bookingId, Double commuterLat, Double commuterLng, String correlationId) {
        DecisionContext ctx = new DecisionContext(NAME, VERSION, correlationId);

        // 1. Retrieve booking — without it we cannot even start.
        TrustedFact<Booking> bookingFact = trusted.getBooking(bookingId);
        ctx.consulted(bookingFact);
        if (!bookingFact.isPresent()) {
            ctx.addReason(ReasonCode.NO_DATA).markFallback().withConfidence(0.0);
            return publish(reject(bookingId, ctx), null, "bookingId=" + bookingId);
        }
        Booking booking = bookingFact.value();
        if (commuterLat == null || commuterLng == null) {
            ctx.addReason(ReasonCode.NO_DATA).markDegraded().withConfidence(0.0);
            return publish(noMatch(booking.getId(), ctx, true), List.of(), summary(booking));
        }

        // 2. Retrieve candidate drivers — only those who pinged recently exist.
        TrustedFact<List<DriverLocation>> activeFact = trusted.getActiveDriverLocations(Duration.ofMinutes(2));
        ctx.consulted(activeFact);
        List<DriverLocation> active = activeFact.value();
        if (active == null || active.isEmpty()) {
            ctx.addReason(ReasonCode.NO_CANDIDATE_DRIVERS).markFallback().withConfidence(0.0);
            return publish(noMatch(booking.getId(), ctx, false), List.of(), summary(booking));
        }

        // 3. Load decline counts and the pickup rank's queue once for all
        //    candidates (not one query per driver).
        List<Long> candidateIds = active.stream()
                .map(DriverLocation::getDriverId).filter(Objects::nonNull).distinct().toList();
        TrustedFact<Map<Long, Integer>> declineFact = trusted.countRecentDeclinesFor(candidateIds);
        ctx.consulted(declineFact);
        Map<Long, Integer> declineCounts = declineFact.asOptional().orElse(Map.of());

        // Queue position: only meaningful if we know which rank — use booking pickup as best-effort label
        Map<Long, Integer> queuePositions = new HashMap<>();
        if (booking.getPickupLocation() != null) {
            TrustedFact<List<QueueTicket>> q = trusted.getQueueAtRank(booking.getPickupLocation());
            ctx.consulted(q);
            List<QueueTicket> tickets = q.asOptional().orElse(List.of());
            for (int i = 0; i < tickets.size(); i++) {
                queuePositions.putIfAbsent(tickets.get(i).getDriverId(), i);
            }
        }

        // 4. Score each candidate from trusted fields only.
        List<DispatchDecision.Candidate> ranked = new ArrayList<>();
        for (DriverLocation loc : active) {
            DispatchDecision.Candidate c = scoreCandidate(loc, commuterLat, commuterLng,
                    declineCounts.getOrDefault(loc.getDriverId(), 0),
                    queuePositions.getOrDefault(loc.getDriverId(), -1), ctx);
            if (c != null) ranked.add(c);
        }
        ranked.sort(Comparator.comparingDouble(DispatchDecision.Candidate::score).reversed());

        if (ranked.isEmpty()) {
            ctx.addReason(ReasonCode.NO_CANDIDATE_DRIVERS).markFallback().withConfidence(0.0);
            return publish(noMatch(booking.getId(), ctx, false), List.of(), summary(booking));
        }

        DispatchDecision.Candidate top = ranked.get(0);
        double confidence = computeConfidence(top, ranked);
        ctx.withConfidence(confidence).addReason(ReasonCode.SCORED);

        // 5. Gate before publishing assignment.
        if (top.score() < props.getDispatch().getMinActionableScore()
                || !gate.isActionable(ctx.seal(), props.getDispatch().getMinActionableConfidence())) {
            ctx.addReason(ReasonCode.INSUFFICIENT_CONFIDENCE).markFallback();
            DispatchDecision degraded = new DispatchDecision(
                    booking.getId(),
                    DispatchDecision.Status.DEGRADED_NO_MATCH,
                    null, ranked, ctx.seal());
            return publish(degraded, ranked, summary(booking));
        }

        DispatchDecision decision = new DispatchDecision(
                booking.getId(),
                DispatchDecision.Status.ASSIGNED,
                top.driverId(),
                ranked,
                ctx.seal());

        // 6. Policy authorisation (no autonomous assignment if degraded).
        if (!policy.isDispatchAutonomous(decision)) {
            DispatchDecision held = new DispatchDecision(
                    booking.getId(),
                    DispatchDecision.Status.DEGRADED_NO_MATCH,
                    null, ranked, decision.metadata());
            return publish(held, ranked, summary(booking));
        }

        return publish(decision, ranked, summary(booking));
    }

    private DispatchDecision.Candidate scoreCandidate(DriverLocation loc,
                                                      double commuterLat,
                                                      double commuterLng,
                                                      int recentDeclines,
                                                      int queuePos,
                                                      DecisionContext ctx) {
        Long driverId = loc.getDriverId();
        if (driverId == null || loc.getLat() == null || loc.getLng() == null) return null;

        double distance = Geo.haversineMeters(commuterLat, commuterLng, loc.getLat(), loc.getLng());
        if (distance > props.getDispatch().getMaxRadiusMeters()) {
            return null; // out of range — never silently include
        }

        TrustedFact<DriverLocation> freshness = trusted.getFreshDriverLocation(driverId);
        ctx.consulted(freshness);
        boolean fresh = freshness.isPresent();
        if (!fresh) {
            // we can rank stale drivers as fallbacks but never autonomously assign them
            ctx.markDegraded();
        }

        List<ReasonCode> notes = new ArrayList<>();
        if (queuePos == 0) notes.add(ReasonCode.QUEUE_POSITION_FIRST);
        else if (queuePos > 0) notes.add(ReasonCode.QUEUE_POSITION_OK);
        if (!fresh) notes.add(ReasonCode.DRIVER_LOCATION_STALE);
        if (recentDeclines > 0) notes.add(ReasonCode.DRIVER_RECENTLY_DECLINED);

        // Score components — all bounded to [0,1]
        double distanceScore = Math.max(0.0, 1.0 - distance / props.getDispatch().getMaxRadiusMeters());
        double freshnessScore = fresh ? 1.0 : 0.3;
        double declineScore = Math.max(0.0, 1.0 - Math.min(recentDeclines, 5) / 5.0);
        double queueScore = queuePos < 0 ? 0.5 : Math.max(0.0, 1.0 - queuePos / 10.0);

        double score = 0.45 * distanceScore + 0.25 * freshnessScore + 0.15 * declineScore + 0.15 * queueScore;

        return new DispatchDecision.Candidate(
                driverId, round(score), round(distance),
                queuePos, recentDeclines, fresh, notes);
    }

    private double computeConfidence(DispatchDecision.Candidate top, List<DispatchDecision.Candidate> all) {
        // Confidence rises with top score and with margin over runner-up.
        double margin = all.size() > 1 ? Math.max(0.0, top.score() - all.get(1).score()) : top.score();
        return round(Math.min(1.0, 0.6 * top.score() + 0.4 * margin));
    }

    private DispatchDecision noMatch(Long bookingId, DecisionContext ctx, boolean degraded) {
        if (degraded) ctx.markDegraded();
        return new DispatchDecision(bookingId,
                degraded ? DispatchDecision.Status.DEGRADED_NO_MATCH : DispatchDecision.Status.NO_MATCH,
                null, List.of(), ctx.seal());
    }

    private DispatchDecision reject(Long bookingId, DecisionContext ctx) {
        return new DispatchDecision(bookingId, DispatchDecision.Status.REJECTED,
                null, List.of(), ctx.seal());
    }

    private DispatchDecision publish(DispatchDecision d,
                                     List<DispatchDecision.Candidate> ignored,
                                     String summary) {
        audit.record(d.metadata(), summary, d);
        return d;
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private static String summary(Booking b) {
        return "bookingId=" + b.getId()
                + " commuterId=" + b.getCommuterId()
                + " status=" + b.getStatus();
    }

    /** Convenience: rank queue-only dispatch (no commuter GPS). */
    public DispatchDecision dispatchByPickupRank(Long bookingId, String correlationId) {
        DecisionContext ctx = new DecisionContext(NAME, VERSION, correlationId);
        TrustedFact<Booking> bookingFact = trusted.getBooking(bookingId);
        ctx.consulted(bookingFact);
        if (!bookingFact.isPresent()) {
            ctx.addReason(ReasonCode.NO_DATA).markFallback().withConfidence(0.0);
            return publish(reject(bookingId, ctx), null, "bookingId=" + bookingId);
        }
        Booking booking = bookingFact.value();
        TrustedFact<List<QueueTicket>> qFact = trusted.getQueueAtRank(booking.getPickupLocation());
        ctx.consulted(qFact);
        if (!qFact.isPresent() || qFact.value().isEmpty()) {
            ctx.addReason(ReasonCode.NO_CANDIDATE_DRIVERS).markFallback().withConfidence(0.0);
            return publish(noMatch(booking.getId(), ctx, false), List.of(), summary(booking));
        }
        QueueTicket head = qFact.value().get(0);
        ctx.addReason(ReasonCode.QUEUE_POSITION_FIRST).addReason(ReasonCode.SCORED).withConfidence(0.85);
        DispatchDecision.Candidate c = new DispatchDecision.Candidate(
                head.getDriverId(), 0.85, Double.NaN, 0, 0, false,
                List.of(ReasonCode.QUEUE_POSITION_FIRST));
        DispatchDecision d = new DispatchDecision(
                booking.getId(),
                DispatchDecision.Status.ASSIGNED,
                head.getDriverId(),
                List.of(c),
                ctx.seal());
        return publish(d, List.of(c), summary(booking));
    }
}
