package com.etekisi.etekisi.agent.safety;

import com.etekisi.etekisi.agent.audit.DecisionAuditLogger;
import com.etekisi.etekisi.agent.dto.ReasonCode;
import com.etekisi.etekisi.agent.dto.SafetyAlert;
import com.etekisi.etekisi.agent.guardrail.*;
import com.etekisi.etekisi.agent.trip.TripGpsPing;
import com.etekisi.etekisi.model.Booking;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Rules-based Safety / Route-Deviation engine.
 *
 * Decisions are deliberately conservative:
 *   - INSUFFICIENT_DATA when GPS history is too thin
 *   - never CRITICAL without multiple breached thresholds
 *   - PolicyEngine downgrades any CRITICAL action to HUMAN_REVIEW_REQUIRED
 *   - never autonomously contacts authorities
 *
 * GPS history isn't yet stored on Booking, so callers pass in a ping
 * window directly. This keeps the agent honest until a TripGps repository
 * exists; if no pings arrive we say so rather than invent.
 */
@Service
public class SafetyAgent {

    public static final String NAME = "SafetyAgent";
    public static final String VERSION = "1.0.0-rules";

    private final TrustedDataService trusted;
    private final AgentProperties props;
    private final PolicyEngine policy;
    private final DecisionAuditLogger audit;

    public SafetyAgent(TrustedDataService trusted,
                       AgentProperties props,
                       PolicyEngine policy,
                       DecisionAuditLogger audit) {
        this.trusted = trusted;
        this.props = props;
        this.policy = policy;
        this.audit = audit;
    }

    public record GpsPing(double lat, double lng, Instant at) {}

    public SafetyAlert evaluate(Long bookingId,
                                List<GpsPing> recentPings,
                                ExpectedRoute expected,
                                String correlationId) {
        DecisionContext ctx = new DecisionContext(NAME, VERSION, correlationId);
        TrustedFact<Booking> b = trusted.getBooking(bookingId);
        ctx.consulted(b);
        if (!b.isPresent()) {
            ctx.addReason(ReasonCode.NO_DATA).markFallback().withConfidence(0.0);
            return publish(insufficient(bookingId, ctx));
        }

        if (recentPings == null || recentPings.size() < props.getSafety().getMinPingsRequired()) {
            ctx.addReason(ReasonCode.INSUFFICIENT_GPS_HISTORY).markFallback().withConfidence(0.2);
            return publish(insufficient(bookingId, ctx));
        }

        boolean deviated = false;
        if (expected != null) {
            double maxOffPath = 0.0;
            for (GpsPing p : recentPings) {
                double off = expected.distanceFromPathMeters(p.lat(), p.lng());
                if (off > maxOffPath) maxOffPath = off;
            }
            if (maxOffPath > props.getSafety().getDeviationThresholdMeters()) {
                deviated = true;
                ctx.addReason(ReasonCode.ROUTE_DEVIATION);
            } else {
                ctx.addReason(ReasonCode.ROUTE_ON_TRACK);
            }
        }

        boolean stationary = isStationary(recentPings, props.getSafety().getStationarySeconds());
        if (stationary) ctx.addReason(ReasonCode.PROLONGED_STATIONARY);

        SafetyAlert.Severity severity;
        SafetyAlert.AlertType type;
        SafetyAlert.Action action;
        double confidence;

        if (deviated && stationary) {
            severity = SafetyAlert.Severity.CRITICAL;
            type = SafetyAlert.AlertType.ROUTE_DEVIATION;
            action = SafetyAlert.Action.OPS_REVIEW;
            confidence = 0.85;
        } else if (deviated) {
            severity = SafetyAlert.Severity.WARN;
            type = SafetyAlert.AlertType.ROUTE_DEVIATION;
            action = SafetyAlert.Action.NOTIFY_USER;
            confidence = 0.7;
        } else if (stationary) {
            severity = SafetyAlert.Severity.WARN;
            type = SafetyAlert.AlertType.PROLONGED_STATIONARY;
            action = SafetyAlert.Action.NOTIFY_USER;
            confidence = 0.6;
        } else {
            severity = SafetyAlert.Severity.INFO;
            type = SafetyAlert.AlertType.NONE;
            action = SafetyAlert.Action.NONE;
            confidence = 0.9;
        }
        ctx.withConfidence(confidence);

        SafetyAlert proposed = new SafetyAlert(bookingId, severity, type, action, ctx.seal());
        SafetyAlert.Action constrained = policy.constrainSafetyAction(proposed);
        SafetyAlert published = new SafetyAlert(bookingId, severity, type, constrained, proposed.metadata());
        return publish(published);
    }

    /**
     * Authoritative-source variant: reads pings from TripGpsPingRepository
     * via TrustedDataService. Use this from event listeners and the REST
     * endpoint; the in-memory variant above stays for unit tests / callers
     * that already have pings in hand.
     */
    public SafetyAlert evaluateFromAuthoritativeSource(Long bookingId,
                                                       Duration window,
                                                       ExpectedRoute expected,
                                                       String correlationId) {
        TrustedFact<List<TripGpsPing>> fact =
                trusted.getTripPingsSince(bookingId, Instant.now().minus(window));
        if (!fact.isPresent() || fact.value().isEmpty()) {
            DecisionContext ctx = new DecisionContext(NAME, VERSION, correlationId)
                    .consulted(fact)
                    .addReason(ReasonCode.INSUFFICIENT_GPS_HISTORY)
                    .markFallback().withConfidence(0.2);
            return publish(insufficient(bookingId, ctx));
        }
        List<GpsPing> pings = new ArrayList<>(fact.value().size());
        for (TripGpsPing p : fact.value()) {
            pings.add(new GpsPing(p.getLat(), p.getLng(), p.getRecordedAt()));
        }
        return evaluate(bookingId, pings, expected, correlationId);
    }

    public SafetyAlert panic(Long bookingId, String correlationId) {
        DecisionContext ctx = new DecisionContext(NAME, VERSION, correlationId)
                .addReason(ReasonCode.PANIC_TRIGGERED).withConfidence(1.0);
        SafetyAlert proposed = new SafetyAlert(bookingId,
                SafetyAlert.Severity.CRITICAL,
                SafetyAlert.AlertType.PANIC_BUTTON,
                SafetyAlert.Action.OPS_REVIEW,
                ctx.seal());
        SafetyAlert.Action constrained = policy.constrainSafetyAction(proposed);
        return publish(new SafetyAlert(bookingId, proposed.severity(), proposed.type(), constrained, proposed.metadata()));
    }

    private boolean isStationary(List<GpsPing> pings, long seconds) {
        if (pings.size() < 2) return false;
        GpsPing first = pings.get(0);
        GpsPing last = pings.get(pings.size() - 1);
        if (Duration.between(first.at(), last.at()).getSeconds() < seconds) return false;
        for (GpsPing p : pings) {
            if (Geo.haversineMeters(first.lat(), first.lng(), p.lat(), p.lng()) > 80.0) return false;
        }
        return true;
    }

    private SafetyAlert insufficient(Long bookingId, DecisionContext ctx) {
        return new SafetyAlert(bookingId,
                SafetyAlert.Severity.INFO,
                SafetyAlert.AlertType.INSUFFICIENT_DATA,
                SafetyAlert.Action.NONE,
                ctx.seal());
    }

    private SafetyAlert publish(SafetyAlert a) {
        audit.record(a.metadata(), "bookingId=" + a.bookingId(), a);
        return a;
    }

    /**
     * Pluggable expected-route abstraction. Real implementations sample the
     * planned polyline; the default below always returns 0 (treats every
     * point as on-path) so callers without a polyline simply fall through
     * to stationary checks.
     */
    public interface ExpectedRoute {
        double distanceFromPathMeters(double lat, double lng);
        ExpectedRoute ALWAYS_ON_PATH = (lat, lng) -> 0.0;
    }
}
