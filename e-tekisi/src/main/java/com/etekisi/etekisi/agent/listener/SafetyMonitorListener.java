package com.etekisi.etekisi.agent.listener;

import com.etekisi.etekisi.agent.dto.SafetyAlert;
import com.etekisi.etekisi.agent.event.AgentEventPublisher;
import com.etekisi.etekisi.agent.event.DriverLocationUpdatedEvent;
import com.etekisi.etekisi.agent.event.SafetyIncidentDetectedEvent;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.agent.observability.CorrelationContext;
import com.etekisi.etekisi.agent.safety.PolylineExpectedRoute;
import com.etekisi.etekisi.agent.safety.SafetyAgent;
import com.etekisi.etekisi.agent.safety.TripRoute;
import com.etekisi.etekisi.agent.safety.TripRouteRepository;
import com.etekisi.etekisi.config.SloMetrics;
import com.etekisi.etekisi.model.Booking;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Re-evaluates safety on every driver location update for any active
 * (ACCEPTED) booking. Critical-severity alerts are re-published as
 * SafetyIncidentDetectedEvent so notification, ops-console, and audit
 * subscribers can react.
 *
 * Async to keep the location-update REST hot path fast. Event ordering is
 * not strict — each evaluation reads the latest authoritative pings, so
 * out-of-order processing produces an out-of-date answer at worst, not an
 * incorrect one.
 */
@Component
public class SafetyMonitorListener {

    private static final Logger log = LoggerFactory.getLogger(SafetyMonitorListener.class);
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final SafetyAgent safety;
    private final TrustedDataService trusted;
    private final AgentEventPublisher events;
    private final DriverLocationThrottler throttler;
    private final ObjectProvider<SloMetrics> slo;

    // Phase 3.1 — optional polyline lookup. Field-injected (with required=false)
    // so the existing 5-arg constructor used in tests stays valid; if no
    // route is stored we fall through to ALWAYS_ON_PATH (no false alarms).
    @Autowired(required = false)
    private TripRouteRepository tripRoutes;

    private final ObjectMapper json = new ObjectMapper();

    public SafetyMonitorListener(SafetyAgent safety,
                                 TrustedDataService trusted,
                                 AgentEventPublisher events,
                                 DriverLocationThrottler throttler,
                                 ObjectProvider<SloMetrics> slo) {
        this.safety = safety;
        this.trusted = trusted;
        this.events = events;
        this.throttler = throttler;
        this.slo = slo;
    }

    @Async
    @EventListener
    public void onDriverLocationUpdated(DriverLocationUpdatedEvent event) {
        try {
            CorrelationContext.set(event.correlationId());
            // Hot-path throttle: skip safety re-evaluation if we just evaluated
            // this driver and they haven't moved meaningfully. GPS persistence
            // happens elsewhere and is NEVER throttled — no data is lost.
            if (!throttler.shouldEvaluate(event.driverId(), event.lat(), event.lng(), event.occurredAt())) {
                log.debug("agent.safety.monitor.throttled driverId={} corr={}",
                        event.driverId(), event.correlationId());
                return;
            }
            TrustedFact<List<Booking>> active = trusted.getActiveBookingsForDriver(event.driverId());
            if (!active.isPresent() || active.value().isEmpty()) return;
            for (Booking b : active.value()) {
                SafetyAgent.ExpectedRoute expected = expectedRouteFor(b.getId());
                SafetyAlert alert = safety.evaluateFromAuthoritativeSource(
                        b.getId(), WINDOW,
                        expected,
                        event.correlationId());
                if (alert.severity() == SafetyAlert.Severity.WARN
                        || alert.severity() == SafetyAlert.Severity.CRITICAL) {
                    events.publish(new SafetyIncidentDetectedEvent(alert, Instant.now(), event.correlationId()));
                    SloMetrics m = slo.getIfAvailable();
                    if (m != null) m.safetyAlert(alert.severity().name());
                }
            }
        } catch (RuntimeException e) {
            log.error("agent.safety.monitor.failed driverId={} error={}",
                    event.driverId(), e.getMessage());
        } finally {
            CorrelationContext.clear();
        }
    }

    /**
     * Resolves the expected route for a booking from {@code agent_schema.trip_routes}.
     * Falls through to {@link SafetyAgent.ExpectedRoute#ALWAYS_ON_PATH} on
     * absent / malformed / repository-down — never invents a polyline.
     */
    private SafetyAgent.ExpectedRoute expectedRouteFor(Long bookingId) {
        if (tripRoutes == null) return SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH;
        try {
            Optional<TripRoute> row = tripRoutes.findByBookingId(bookingId);
            if (row.isEmpty()) return SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH;
            return PolylineExpectedRoute.fromJson(row.get().getPolylineJson(), json);
        } catch (RuntimeException repoFailure) {
            log.warn("agent.safety.route_lookup_failed bookingId={} error={}",
                    bookingId, repoFailure.getMessage());
            return SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH;
        }
    }
}
