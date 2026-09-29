package com.etekisi.etekisi.agent.listener;

import com.etekisi.etekisi.agent.event.TripStartedEvent;
import com.etekisi.etekisi.agent.insight.InsightCode;
import com.etekisi.etekisi.agent.insight.OperationalInsight;
import com.etekisi.etekisi.agent.insight.OperationalInsightRepository;
import com.etekisi.etekisi.agent.observability.CorrelationContext;
import com.etekisi.etekisi.config.SloMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Phase 2.4 — closes the loop on {@link TripStartedEvent}.
 *
 * <p>Before this listener existed the event was published but never consumed,
 * making it dead weight in the audit trail. The roadmap allowed either
 * deletion or wiring; we wire because operators benefit from a per-trip
 * "started OK" timeline marker for incident reconstruction.
 *
 * <p>Anti-hallucination posture: the row is a 1:1 projection of a trusted
 * platform event. No new judgment, no new severity. INFO only.
 *
 * <p>Failures are absorbed and logged loudly — the trip is already started in
 * the system of record (BookingController), so a missed insight row is an
 * observability gap, never a correctness bug.
 */
@Component
public class TripStartedAuditListener {

    private static final Logger log = LoggerFactory.getLogger(TripStartedAuditListener.class);

    private final OperationalInsightRepository repo;
    private final ObjectProvider<SloMetrics> sloProvider;

    public TripStartedAuditListener(OperationalInsightRepository repo,
                                    ObjectProvider<SloMetrics> sloProvider) {
        this.repo = repo;
        this.sloProvider = sloProvider;
    }

    @EventListener
    public void onTripStarted(TripStartedEvent event) {
        try {
            CorrelationContext.set(event.correlationId());
            OperationalInsight insight = new OperationalInsight();
            insight.setBookingId(event.bookingId());
            insight.setDriverId(event.driverId());
            insight.setCommuterId(event.commuterId());
            insight.setType(OperationalInsight.Type.METRIC);
            insight.setCode(InsightCode.TRIP_STARTED_OK);
            insight.setSeverity(OperationalInsight.Severity.INFO);
            insight.setMessage(buildMessage(event));
            insight.setCorrelationId(event.correlationId());
            insight.setCreatedAt(Instant.now());

            try {
                repo.save(insight);
            } catch (RuntimeException persistFailure) {
                log.warn("agent.insight.persist_failed code=TRIP_STARTED_OK bookingId={} error={}",
                        event.bookingId(), persistFailure.getMessage());
            }
        } catch (RuntimeException e) {
            log.error("agent.trip_started.audit.failed bookingId={} error={}",
                    event.bookingId(), e.getMessage());
            SloMetrics m = sloProvider.getIfAvailable();
            if (m != null) m.eventDropped("TripStartedAuditListener");
        } finally {
            CorrelationContext.clear();
        }
    }

    /** Bounded, evidence-only message; no narrative. */
    private static String buildMessage(TripStartedEvent e) {
        Double fare = e.fare();
        String body = "Trip started bookingId=" + e.bookingId()
                + " driverId=" + e.driverId()
                + " commuterId=" + e.commuterId()
                + (fare != null ? (" fare=" + fare) : "");
        return body.length() > 512 ? body.substring(0, 512) : body;
    }
}
