package com.etekisi.etekisi.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Pre-registered, named counters for the eight SLO metrics enumerated in the
 * production roadmap (Phase 1.4).
 *
 * <p>The {@link MeterRegistry} is wrapped in an {@link ObjectProvider} so the
 * class is safe to inject even in tests / contexts where Actuator is absent.
 * Increment calls are no-ops when no registry is available — the agent layer
 * already follows this pattern via {@code DecisionAuditLogger}.
 *
 * <p>Once Actuator + Prometheus are on the classpath these counters appear at
 * {@code /actuator/prometheus} as e.g. {@code tekisi_bookings_created_total}.
 */
@Component
public class SloMetrics {

    private final MeterRegistry registry;

    public SloMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable();
    }

    public void bookingCreated() {
        increment("tekisi.bookings.created", Tags.empty());
    }

    public void bookingCompleted() {
        increment("tekisi.bookings.completed", Tags.empty());
    }

    public void bookingCancelled(String reason) {
        increment("tekisi.bookings.cancelled", Tags.of("reason", safe(reason)));
    }

    public void dispatchNoMatch() {
        increment("tekisi.dispatch.no_match", Tags.empty());
    }

    public void safetyAlert(String severity) {
        increment("tekisi.safety.alerts", Tags.of("severity", safe(severity)));
    }

    public void etaFallbackUsed() {
        increment("tekisi.eta.fallback_used", Tags.empty());
    }

    public void routingTimeout() {
        increment("tekisi.routing.timeouts", Tags.empty());
    }

    public void auditPersistFailure() {
        increment("tekisi.audit.persist_failures", Tags.empty());
    }

    public void eventDropped(String listener) {
        increment("tekisi.events.dropped", Tags.of("listener", safe(listener)));
    }

    private void increment(String name, Tags tags) {
        if (registry == null) return;
        Counter.builder(name).tags(tags).register(registry).increment();
    }

    private static String safe(String s) {
        // Closed-vocabulary defense: tag values must never be free-text from
        // user input. Callers should pass enum names or known reasons. We
        // still apply a length cap as a belt-and-braces guard.
        if (s == null) return "unknown";
        return s.length() > 32 ? s.substring(0, 32) : s;
    }
}
