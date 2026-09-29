package com.etekisi.etekisi.agent.insight;

import com.etekisi.etekisi.agent.event.TripCompletedEvent;
import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.agent.trip.TripGpsPing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Operator-side, deterministic, evidence-only anomaly detector.
 *
 * Inputs: a {@link TripCompletedEvent} (trusted system fact) and authoritative
 * trip GPS history (trusted-data only).
 *
 * Outputs: zero or more {@link OperationalInsight} rows with bounded codes,
 * bounded severity, and bounded message text. Operator dashboards read these.
 *
 * Anti-hallucination posture:
 *   - never invents a fact about the trip; pure projection of trusted state
 *   - reads pings via {@link TrustedDataService} (single source of truth)
 *   - short-circuits cleanly when a referenced fact is missing
 *   - emits only enum-keyed signals — no free-text alerts
 *   - no LLMs, no scoring models — bounded thresholds only
 */
@Component
public class TripAnomalyAgent {

    private static final Logger log = LoggerFactory.getLogger(TripAnomalyAgent.class);

    /** Generous lookback for ping history of the just-completed trip. */
    private static final int PING_LOOKBACK = 1024;

    private final TrustedDataService trusted;
    private final AgentProperties props;

    public TripAnomalyAgent(TrustedDataService trusted, AgentProperties props) {
        this.trusted = trusted;
        this.props = props;
    }

    /**
     * Inspect a completed trip and produce zero or more bounded insights.
     * Pure function of the event + trusted facts; never throws.
     */
    public List<OperationalInsight> inspectCompletedTrip(TripCompletedEvent event) {
        List<OperationalInsight> out = new ArrayList<>();
        if (event == null || event.bookingId() == null) return out;

        // 1. Always emit a baseline TRIP_COMPLETED_OK metric (low value but
        //    great for ops volume dashboards). If subsequent checks add WARN
        //    signals, the baseline still stays as INFO context.
        out.add(metric(event, InsightCode.TRIP_COMPLETED_OK,
                "Trip completed bookingId=" + event.bookingId()
                        + " driverId=" + event.driverId()));

        // 2. Fare-band signals. Every check is bounded by configured thresholds.
        Double fare = event.fare();
        if (fare == null || fare <= 0.0) {
            out.add(anomaly(event, InsightCode.ZERO_FARE_TRIP,
                    OperationalInsight.Severity.WARN,
                    "Trip completed with zero or null fare — payment / wallet review."));
        } else if (fare > props.getInsight().getHighFareRand()) {
            out.add(anomaly(event, InsightCode.UNUSUAL_HIGH_FARE,
                    OperationalInsight.Severity.WARN,
                    "Fare R" + fare + " exceeds threshold R" + props.getInsight().getHighFareRand()));
        }

        // 3. GPS-history signal. A completed trip with too few pings means we
        //    cannot reconstruct the route — operationally important for safety
        //    audits, dispute resolution, and wastage detection.
        TrustedFact<List<TripGpsPing>> pings = trusted.getRecentTripPings(event.bookingId(), PING_LOOKBACK);
        if (!pings.isPresent() || pings.value().size() < props.getInsight().getMinPingsForTrip()) {
            int count = pings.isPresent() ? pings.value().size() : 0;
            out.add(anomaly(event, InsightCode.NO_GPS_HISTORY,
                    OperationalInsight.Severity.WARN,
                    "Only " + count + " GPS pings recorded for this trip "
                            + "(threshold " + props.getInsight().getMinPingsForTrip() + ")"));
        }

        log.info("agent.insight.trip_inspected bookingId={} insights={}",
                event.bookingId(), out.size());
        return out;
    }

    // -- helpers (bounded message construction) ------------------------------

    private OperationalInsight metric(TripCompletedEvent e, InsightCode code, String message) {
        OperationalInsight i = base(e, code, OperationalInsight.Type.METRIC,
                OperationalInsight.Severity.INFO, message);
        return i;
    }

    private OperationalInsight anomaly(TripCompletedEvent e, InsightCode code,
                                       OperationalInsight.Severity severity, String message) {
        return base(e, code, OperationalInsight.Type.ANOMALY, severity, message);
    }

    private OperationalInsight base(TripCompletedEvent e, InsightCode code,
                                    OperationalInsight.Type type,
                                    OperationalInsight.Severity severity,
                                    String message) {
        OperationalInsight i = new OperationalInsight();
        i.setBookingId(e.bookingId());
        i.setDriverId(e.driverId());
        i.setCommuterId(e.commuterId());
        i.setType(type);
        i.setCode(code);
        i.setSeverity(severity);
        i.setMessage(truncate(message));
        i.setCorrelationId(e.correlationId());
        i.setCreatedAt(Instant.now());
        return i;
    }

    private static String truncate(String m) {
        if (m == null) return "";
        return m.length() > 512 ? m.substring(0, 512) : m;
    }
}
