package com.etekisi.etekisi.agent.listener;

import com.etekisi.etekisi.agent.dto.SafetyAlert;
import com.etekisi.etekisi.agent.event.SafetyIncidentDetectedEvent;
import com.etekisi.etekisi.agent.observability.CorrelationContext;
import com.etekisi.etekisi.config.SloMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Phase 3.4 — fans out CRITICAL safety alerts to the operator paging channel.
 *
 * <p>Two outputs:
 * <ul>
 *   <li>A loud structured log line — always emitted. Operators tail this
 *       in any environment.</li>
 *   <li>A POST to {@code tekisi.agent.safety.alert-webhook} (e.g. PagerDuty
 *       Events v2, Slack incoming webhook, Twilio HTTP trigger) — emitted
 *       only when the property is set. Default is unset so dev/test never
 *       call out.</li>
 * </ul>
 *
 * <p>Async by design: the panic POST returns to the commuter immediately;
 * the webhook fan-out happens on the agent executor with the same
 * correlation-id propagated via {@link CorrelationContext}.
 *
 * <p>Anti-noise: only severity = CRITICAL pages. WARN-class alerts (route
 * deviation, prolonged stationary) go to dashboards only.
 *
 * <p>Failure isolation: webhook failures are logged + counted via
 * {@code tekisi.events.dropped{listener=OpsAlertListener}} but never
 * rethrown — a paging outage cannot block the safety pipeline.
 */
@Component
public class OpsAlertListener {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertListener.class);

    private final String webhookUrl;
    private final ObjectProvider<SloMetrics> sloProvider;
    private final RestClient http;

    public OpsAlertListener(@Value("${tekisi.agent.safety.alert-webhook:}") String webhookUrl,
                            ObjectProvider<SloMetrics> sloProvider) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.sloProvider = sloProvider;
        this.http = RestClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Async
    @EventListener
    public void onSafetyIncidentDetected(SafetyIncidentDetectedEvent event) {
        try {
            CorrelationContext.set(event.correlationId());
            SafetyAlert alert = event.alert();
            if (alert == null || alert.severity() != SafetyAlert.Severity.CRITICAL) {
                return;
            }
            // Always log loudly — even when no webhook configured.
            log.error("agent.ops.alert severity=CRITICAL type={} bookingId={} action={} corr={}",
                    alert.type(), alert.bookingId(), alert.recommendedAction(), event.correlationId());

            if (webhookUrl.isEmpty()) {
                return;
            }
            Map<String, Object> payload = Map.of(
                    "service", "tekisi",
                    "severity", "critical",
                    "summary", "Tekisi safety alert — " + alert.type(),
                    "bookingId", alert.bookingId(),
                    "type", alert.type().name(),
                    "action", alert.recommendedAction().name(),
                    "correlationId", event.correlationId(),
                    "occurredAt", (event.occurredAt() == null ? Instant.now() : event.occurredAt()).toString()
            );
            try {
                http.post().uri(webhookUrl).body(payload).retrieve().toBodilessEntity();
                log.info("agent.ops.alert.delivered bookingId={} corr={}",
                        alert.bookingId(), event.correlationId());
            } catch (RuntimeException webhookFail) {
                log.error("agent.ops.alert.webhook_failed bookingId={} error={}",
                        alert.bookingId(), webhookFail.getMessage());
                SloMetrics m = sloProvider.getIfAvailable();
                if (m != null) m.eventDropped("OpsAlertListener");
            }
        } catch (RuntimeException outer) {
            // Never let the listener kill the publisher.
            log.error("agent.ops.alert.failed corr={} error={}",
                    event.correlationId(), outer.getMessage());
        } finally {
            CorrelationContext.clear();
        }
    }

    /** Bounded request timeout — paging is best-effort. */
    public Duration timeout() { return Duration.ofSeconds(2); }
}
