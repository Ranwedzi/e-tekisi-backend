package com.etekisi.etekisi.agent.listener;

import com.etekisi.etekisi.agent.dispatch.DispatchAgent;
import com.etekisi.etekisi.agent.dto.DispatchDecision;
import com.etekisi.etekisi.agent.event.BookingCreatedEvent;
import com.etekisi.etekisi.agent.observability.CorrelationContext;
import com.etekisi.etekisi.config.SloMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On every booking creation, runs the dispatch agent in ADVISORY mode and
 * writes a decision_audit row. NEVER mutates the booking or assigns a driver
 * — the existing BookingController.acceptBooking remains the system of record.
 *
 * Goal: build up real production data on dispatch quality (confidence, fallback
 * rate, agreement with human-driver-acceptance) before flipping to autonomous.
 */
@Component
public class AdvisoryDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryDispatchListener.class);
    private final DispatchAgent dispatch;
    private final ObjectProvider<SloMetrics> slo;

    public AdvisoryDispatchListener(DispatchAgent dispatch, ObjectProvider<SloMetrics> slo) {
        this.dispatch = dispatch;
        this.slo = slo;
    }

    @EventListener
    public void onBookingCreated(BookingCreatedEvent event) {
        try {
            CorrelationContext.set(event.correlationId());
            DispatchDecision decision;
            if (event.commuterLat() != null && event.commuterLng() != null) {
                decision = dispatch.dispatch(event.bookingId(),
                        event.commuterLat(), event.commuterLng(), event.correlationId());
            } else {
                // No commuter GPS — fall back to rank-queue dispatch.
                decision = dispatch.dispatchByPickupRank(event.bookingId(), event.correlationId());
            }
            log.info("agent.dispatch.advisory bookingId={} status={} confidence={} fallback={} degraded={}",
                    decision.bookingId(), decision.status(),
                    decision.metadata().confidence(),
                    decision.metadata().fallbackUsed(),
                    decision.metadata().degraded());
            // Operator-visible SLO: count NO_MATCH / DEGRADED_NO_MATCH so we
            // can alert on dispatch starvation independently of throughput.
            if (decision.status() == DispatchDecision.Status.NO_MATCH
                    || decision.status() == DispatchDecision.Status.DEGRADED_NO_MATCH) {
                SloMetrics m = slo.getIfAvailable();
                if (m != null) m.dispatchNoMatch();
            }
        } catch (RuntimeException e) {
            log.error("agent.dispatch.advisory.failed bookingId={} error={}",
                    event.bookingId(), e.getMessage());
        } finally {
            CorrelationContext.clear();
        }
    }
}
