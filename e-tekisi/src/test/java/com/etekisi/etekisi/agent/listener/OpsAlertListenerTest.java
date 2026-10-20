package com.etekisi.etekisi.agent.listener;

import com.etekisi.etekisi.agent.dto.SafetyAlert;
import com.etekisi.etekisi.agent.event.SafetyIncidentDetectedEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** CRITICAL safety alerts reach the paging webhook, with retries, in the configured format. */
class OpsAlertListenerTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger failFirst = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            int status = failFirst.getAndDecrement() > 0 ? 503 : 202;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private OpsAlertListener listener(String format, String secret) {
        return new OpsAlertListener(url(), format, secret, "https://tekisi.test/ops", Duration.ofMillis(10),
                new StaticListableBeanFactory().getBeanProvider(com.etekisi.etekisi.config.SloMetrics.class));
    }

    private static SafetyIncidentDetectedEvent panic(long bookingId) {
        SafetyAlert alert = new SafetyAlert(bookingId, SafetyAlert.Severity.CRITICAL,
                SafetyAlert.AlertType.PANIC_BUTTON, SafetyAlert.Action.HUMAN_REVIEW_REQUIRED, null);
        return new SafetyIncidentDetectedEvent(alert, Instant.now(), "corr-1");
    }

    @Test
    void retriesUntilTheWebhookAcceptsAndSendsTheSecret() {
        failFirst.set(2);
        listener("generic", "Bearer s3cret").onSafetyIncidentDetected(panic(42));
        assertThat(bodies).hasSize(3);
        assertThat(authHeaders).containsOnly("Bearer s3cret");
        assertThat(bodies.get(2))
                .contains("\"text\":\"e-Tekisi safety alert: PANIC_BUTTON on trip #42")
                .contains("https://tekisi.test/ops")
                .contains("\"bookingId\":42");
    }

    @Test
    void givesUpAfterThreeAttempts() {
        failFirst.set(10);
        listener("generic", "").onSafetyIncidentDetected(panic(43));
        assertThat(bodies).hasSize(3);
        assertThat(authHeaders).containsOnly("null");
    }

    @Test
    void pagerDutyFormatUsesTheRoutingKey() {
        listener("pagerduty", "R0UTINGKEY").onSafetyIncidentDetected(panic(44));
        assertThat(bodies).hasSize(1);
        assertThat(authHeaders).containsOnly("null");
        assertThat(bodies.get(0))
                .contains("\"routing_key\":\"R0UTINGKEY\"")
                .contains("\"event_action\":\"trigger\"")
                .contains("\"dedup_key\":\"tekisi-safety-44-PANIC_BUTTON\"")
                .contains("\"severity\":\"critical\"");
    }

    @Test
    void warnAlertsDoNotPage() {
        SafetyAlert warn = new SafetyAlert(45L, SafetyAlert.Severity.WARN,
                SafetyAlert.AlertType.ROUTE_DEVIATION, SafetyAlert.Action.OPS_REVIEW, null);
        listener("generic", "").onSafetyIncidentDetected(new SafetyIncidentDetectedEvent(warn, Instant.now(), "c"));
        assertThat(bodies).isEmpty();
    }
}
