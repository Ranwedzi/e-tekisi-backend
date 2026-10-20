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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3.4 — fans out CRITICAL safety alerts to the operator paging channel.
 *
 * <p>Two outputs:
 * <ul>
 *   <li>A loud structured log line — always emitted. Operators tail this
 *       in any environment.</li>
 *   <li>A POST to {@code tekisi.agent.safety.alert-webhook}, emitted only
 *       when it is set (default unset, so dev/test never call out). Format
 *       {@code generic} sends a flat JSON body with a {@code text} field
 *       (Slack / Teams incoming webhooks, SMS gateways, Opsgenie-style HTTP
 *       triggers) and sends {@code alert-webhook-secret}, if set, as the
 *       Authorization header. Format {@code pagerduty} sends a PagerDuty
 *       Events v2 event with the secret as its routing key.</li>
 * </ul>
 *
 * <p>Async by design: the panic POST returns to the commuter immediately;
 * the webhook fan-out happens on the agent executor with the same
 * correlation-id propagated via {@link CorrelationContext}. Each attempt
 * times out after {@link #timeout()}; up to three attempts are made.
 *
 * <p>Anti-noise: only severity = CRITICAL pages. WARN-class alerts (route
 * deviation, prolonged stationary) go to dashboards only.
 *
 * <p>Failure isolation: webhook failures are logged + counted via
 * {@code tekisi.events.dropped{listener=OpsAlertListener}} but never
 * rethrown — a paging outage cannot block the safety pipeline. The
 * {@code tekisi_safety_unacknowledged} gauge is the second line: monitoring
 * pages if an alert sits unacknowledged on /ops.
 */
@Component
public class OpsAlertListener {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertListener.class);
    private static final int ATTEMPTS = 3;

    private final String webhookUrl;
    private final String format;
    private final String secret;
    private final String opsConsoleUrl;
    private final Duration retryDelay;
    private final ObjectProvider<SloMetrics> sloProvider;
    private final RestClient http;

    public OpsAlertListener(@Value("${tekisi.agent.safety.alert-webhook:}") String webhookUrl,
                            @Value("${tekisi.agent.safety.alert-webhook-format:generic}") String format,
                            @Value("${tekisi.agent.safety.alert-webhook-secret:}") String secret,
                            @Value("${tekisi.agent.safety.ops-console-url:}") String opsConsoleUrl,
                            @Value("${tekisi.agent.safety.alert-retry-delay:2s}") Duration retryDelay,
                            ObjectProvider<SloMetrics> sloProvider) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.format = format == null ? "generic" : format.trim().toLowerCase();
        this.secret = secret == null ? "" : secret.trim();
        this.opsConsoleUrl = opsConsoleUrl == null ? "" : opsConsoleUrl.trim();
        this.retryDelay = retryDelay;
        this.sloProvider = sloProvider;
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(timeout());
        timeouts.setReadTimeout(timeout());
        this.http = RestClient.builder()
                .requestFactory(timeouts)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        if (this.webhookUrl.isEmpty()) {
            log.warn("agent.ops.alert.webhook_unset CRITICAL safety alerts will only be logged; "
                    + "set SAFETY_ALERT_WEBHOOK to page on-call");
        }
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
            Map<String, Object> payload = payload(alert, event);
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                try {
                    RestClient.RequestBodySpec req = http.post().uri(webhookUrl);
                    if (!secret.isEmpty() && !"pagerduty".equals(format)) {
                        req = req.header(HttpHeaders.AUTHORIZATION, secret);
                    }
                    req.body(payload).retrieve().toBodilessEntity();
                    log.info("agent.ops.alert.delivered bookingId={} attempt={} corr={}",
                            alert.bookingId(), attempt, event.correlationId());
                    return;
                } catch (RuntimeException webhookFail) {
                    log.error("agent.ops.alert.webhook_failed bookingId={} attempt={} error={}",
                            alert.bookingId(), attempt, webhookFail.getMessage());
                    if (attempt < ATTEMPTS) sleep(retryDelay.multipliedBy(attempt));
                }
            }
            SloMetrics m = sloProvider.getIfAvailable();
            if (m != null) m.eventDropped("OpsAlertListener");
        } catch (RuntimeException outer) {
            // Never let the listener kill the publisher.
            log.error("agent.ops.alert.failed corr={} error={}",
                    event.correlationId(), outer.getMessage());
        } finally {
            CorrelationContext.clear();
        }
    }

    /** The webhook body for the configured format. Package-private for tests. */
    Map<String, Object> payload(SafetyAlert alert, SafetyIncidentDetectedEvent event) {
        String occurredAt = (event.occurredAt() == null ? Instant.now() : event.occurredAt()).toString();
        String summary = "e-Tekisi safety alert: " + alert.type() + " on trip #" + alert.bookingId();
        if ("pagerduty".equals(format)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("bookingId", alert.bookingId());
            details.put("type", alert.type().name());
            details.put("action", alert.recommendedAction().name());
            details.put("correlationId", event.correlationId());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("summary", summary);
            body.put("source", "e-tekisi");
            body.put("severity", "critical");
            body.put("timestamp", occurredAt);
            body.put("custom_details", details);
            Map<String, Object> pd = new LinkedHashMap<>();
            pd.put("routing_key", secret);
            pd.put("event_action", "trigger");
            pd.put("dedup_key", "tekisi-safety-" + alert.bookingId() + "-" + alert.type());
            pd.put("payload", body);
            if (!opsConsoleUrl.isEmpty()) pd.put("links", List.of(Map.of("href", opsConsoleUrl, "text", "Ops console")));
            return pd;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", summary + ". Action: " + alert.recommendedAction()
                + (opsConsoleUrl.isEmpty() ? "" : ". Acknowledge it at " + opsConsoleUrl));
        m.put("service", "tekisi");
        m.put("severity", "critical");
        m.put("summary", summary);
        m.put("bookingId", alert.bookingId());
        m.put("type", alert.type().name());
        m.put("action", alert.recommendedAction().name());
        m.put("correlationId", event.correlationId());
        m.put("occurredAt", occurredAt);
        if (!opsConsoleUrl.isEmpty()) m.put("opsConsoleUrl", opsConsoleUrl);
        return m;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Bounded request timeout — paging is best-effort. */
    public Duration timeout() { return Duration.ofSeconds(2); }
}
