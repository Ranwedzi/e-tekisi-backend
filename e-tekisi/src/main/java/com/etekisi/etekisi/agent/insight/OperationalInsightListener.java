package com.etekisi.etekisi.agent.insight;

import com.etekisi.etekisi.agent.dto.SafetyAlert;
import com.etekisi.etekisi.agent.event.SafetyIncidentDetectedEvent;
import com.etekisi.etekisi.agent.event.TripCompletedEvent;
import com.etekisi.etekisi.agent.observability.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Operator-side event consumer. Persists bounded {@link OperationalInsight}
 * rows from trusted system events.
 *
 * Anti-hallucination posture:
 *   - inputs are platform-internal events (not user input, not LLM output)
 *   - {@link TripAnomalyAgent} produces the anomaly insights deterministically
 *   - {@link #onSafetyIncidentDetected(SafetyIncidentDetectedEvent)} projects
 *     existing SafetyAlert facts 1:1 — no new judgment, no new severity
 *   - listener never throws; any persistence failure is logged and isolated
 *     so the rest of the agent pipeline keeps running
 */
@Component
public class OperationalInsightListener {

    private static final Logger log = LoggerFactory.getLogger(OperationalInsightListener.class);

    private final TripAnomalyAgent anomalies;
    private final OperationalInsightRepository repo;

    public OperationalInsightListener(TripAnomalyAgent anomalies,
                                      OperationalInsightRepository repo) {
        this.anomalies = anomalies;
        this.repo = repo;
    }

    @EventListener
    public void onTripCompleted(TripCompletedEvent event) {
        try {
            CorrelationContext.set(event.correlationId());
            List<OperationalInsight> insights = anomalies.inspectCompletedTrip(event);
            for (OperationalInsight i : insights) {
                try {
                    repo.save(i);
                } catch (RuntimeException persistFailure) {
                    log.warn("agent.insight.persist_failed code={} bookingId={} error={}",
                            i.getCode(), i.getBookingId(), persistFailure.getMessage());
                }
            }
        } catch (RuntimeException outer) {
            log.error("agent.insight.trip_completed.failed bookingId={} error={}",
                    event.bookingId(), outer.getMessage());
        } finally {
            CorrelationContext.clear();
        }
    }

    @EventListener
    public void onSafetyIncidentDetected(SafetyIncidentDetectedEvent event) {
        try {
            CorrelationContext.set(event.correlationId());
            SafetyAlert alert = event.alert();
            if (alert == null) return;

            OperationalInsight insight = new OperationalInsight();
            insight.setBookingId(alert.bookingId());
            insight.setType(OperationalInsight.Type.ALERT);
            insight.setCode(mapAlertType(alert.type()));
            insight.setSeverity(mapSeverity(alert.severity()));
            insight.setMessage(buildAlertMessage(alert));
            insight.setCorrelationId(event.correlationId());
            insight.setCreatedAt(Instant.now());

            repo.save(insight);
        } catch (RuntimeException e) {
            log.error("agent.insight.safety_incident.failed correlationId={} error={}",
                    event.correlationId(), e.getMessage());
        } finally {
            CorrelationContext.clear();
        }
    }

    /** Closed mapping from SafetyAlert.AlertType to InsightCode. No free text. */
    private static InsightCode mapAlertType(SafetyAlert.AlertType type) {
        if (type == null) return InsightCode.SAFETY_INCIDENT;
        return switch (type) {
            case ROUTE_DEVIATION -> InsightCode.SAFETY_DEVIATION;
            case PROLONGED_STATIONARY -> InsightCode.SAFETY_STATIONARY;
            case PANIC_BUTTON -> InsightCode.SAFETY_PANIC;
            case INSUFFICIENT_DATA, NONE -> InsightCode.SAFETY_INCIDENT;
        };
    }

    /** Severity mapping is 1:1 — we never escalate or downgrade. */
    private static OperationalInsight.Severity mapSeverity(SafetyAlert.Severity s) {
        if (s == null) return OperationalInsight.Severity.INFO;
        return switch (s) {
            case INFO -> OperationalInsight.Severity.INFO;
            case WARN -> OperationalInsight.Severity.WARN;
            case CRITICAL -> OperationalInsight.Severity.CRITICAL;
        };
    }

    /** Bounded, evidence-only message; no narrative. */
    private static String buildAlertMessage(SafetyAlert a) {
        String body = "Safety alert bookingId=" + a.bookingId()
                + " type=" + a.type()
                + " severity=" + a.severity()
                + " action=" + a.recommendedAction();
        return body.length() > 512 ? body.substring(0, 512) : body;
    }
}
