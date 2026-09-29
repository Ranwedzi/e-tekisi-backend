package com.etekisi.etekisi.agent.audit;

import com.etekisi.etekisi.agent.dto.DecisionMetadata;
import com.etekisi.etekisi.agent.dto.ReasonCode;
import com.etekisi.etekisi.config.SloMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Persists a DecisionAudit row for every published decision and also emits
 * a structured log line. Failures here NEVER throw — auditing must not
 * break operational flows, but we log loudly so monitoring can catch gaps.
 */
@Service
public class DecisionAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(DecisionAuditLogger.class);

    private final DecisionAuditRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectProvider<MeterRegistry> meterRegistry;

    // Field-injected to keep the existing 2-arg constructor signature (used by
    // tests via Spring) untouched.
    @Autowired(required = false)
    private ObjectProvider<SloMetrics> sloProvider;

    public DecisionAuditLogger(DecisionAuditRepository repo,
                               ObjectProvider<MeterRegistry> meterRegistry) {
        this.repo = repo;
        this.meterRegistry = meterRegistry;
    }

    public void record(DecisionMetadata md, String inputSummary, Object output) {
        if (md == null) return;
        try {
            DecisionAudit row = new DecisionAudit();
            row.setDecisionId(md.decisionId().toString());
            row.setAgent(md.agent());
            row.setAgentVersion(md.agentVersion());
            row.setComputedAt(md.computedAt());
            row.setLatencyMs(md.latencyMs());
            row.setConfidence(md.confidence());
            row.setConfidenceLevel(md.confidenceLevel().name());
            row.setFallbackUsed(md.fallbackUsed());
            row.setDegraded(md.degraded());
            row.setCorrelationId(md.correlationId());
            row.setReasonCodes(md.reasonCodes().stream().map(ReasonCode::name).collect(Collectors.joining(",")));
            row.setSourcesConsulted(String.join(",", md.sourcesConsulted()));
            row.setInputSummary(truncate(inputSummary, 1024));
            row.setOutputJson(safeJson(output));
            repo.save(row);
            log.info("agent.decision agent={} version={} confidence={} level={} fallback={} degraded={} reasons={}",
                    md.agent(), md.agentVersion(), md.confidence(), md.confidenceLevel(),
                    md.fallbackUsed(), md.degraded(), row.getReasonCodes());
            recordMetrics(md);
        } catch (Exception e) {
            log.error("agent.audit.failed agent={} decisionId={} error={}",
                    md.agent(), md.decisionId(), e.getMessage());
            // Operator-visible: a sustained non-zero rate of audit-persist
            // failures is a P1 — auditability is the foundation of the
            // anti-hallucination posture.
            if (sloProvider != null) {
                SloMetrics m = sloProvider.getIfAvailable();
                if (m != null) m.auditPersistFailure();
            }
        }
    }

    private void recordMetrics(DecisionMetadata md) {
        MeterRegistry mr = meterRegistry.getIfAvailable();
        if (mr == null) return;
        Tags tags = Tags.of(
                "agent", md.agent(),
                "level", md.confidenceLevel().name(),
                "fallback", Boolean.toString(md.fallbackUsed()),
                "degraded", Boolean.toString(md.degraded()));
        mr.counter("tekisi.agent.decisions", tags).increment();
        mr.timer("tekisi.agent.latency", "agent", md.agent())
                .record(md.latencyMs(), TimeUnit.MILLISECONDS);
        mr.summary("tekisi.agent.confidence", "agent", md.agent())
                .record(md.confidence());
        if (md.fallbackUsed()) {
            mr.counter("tekisi.agent.fallbacks", "agent", md.agent()).increment();
        }
    }

    private String safeJson(Object o) {
        if (o == null) return null;
        try {
            return truncate(mapper.writeValueAsString(o), 8_000);
        } catch (JsonProcessingException e) {
            return "{\"_serializationError\":true}";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
