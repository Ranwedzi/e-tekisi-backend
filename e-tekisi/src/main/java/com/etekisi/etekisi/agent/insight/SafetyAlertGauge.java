package com.etekisi.etekisi.agent.insight;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code tekisi_safety_unacknowledged}: CRITICAL alerts from the last 24
 * hours that no operator has acknowledged. Monitoring pages on-call when it
 * stays above zero, so an alert is escalated even if the webhook page was
 * missed. One indexed count per scrape.
 */
@Component
public class SafetyAlertGauge {

    public SafetyAlertGauge(ObjectProvider<MeterRegistry> registry, OperationalInsightRepository insights) {
        MeterRegistry r = registry.getIfAvailable();
        if (r == null) return;
        Gauge.builder("tekisi.safety.unacknowledged", insights, repo ->
                        repo.countBySeverityAndAcknowledgedAtIsNullAndCreatedAtAfter(
                                OperationalInsight.Severity.CRITICAL, Instant.now().minus(Duration.ofHours(24))))
                .description("CRITICAL safety alerts in the last 24h that no operator has acknowledged")
                .register(r);
    }
}
