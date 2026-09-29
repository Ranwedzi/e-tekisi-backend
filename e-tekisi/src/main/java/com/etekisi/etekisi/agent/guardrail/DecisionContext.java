package com.etekisi.etekisi.agent.guardrail;

import com.etekisi.etekisi.agent.dto.DecisionMetadata;
import com.etekisi.etekisi.agent.dto.ReasonCode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable accumulator used while an agent is constructing a decision.
 * Tracks reason codes, sources, fallback/degraded flags, and timing,
 * then materialises a final {@link DecisionMetadata} record.
 *
 * One per decision — never shared across requests.
 */
public final class DecisionContext {

    private final String agent;
    private final String agentVersion;
    private final String correlationId;
    private final long startedNanos = System.nanoTime();

    private final List<ReasonCode> reasons = new ArrayList<>();
    private final Set<String> sources = new LinkedHashSet<>();

    private double confidence = 0.0;
    private boolean fallbackUsed = false;
    private boolean degraded = false;

    public DecisionContext(String agent, String agentVersion, String correlationId) {
        this.agent = agent;
        this.agentVersion = agentVersion;
        this.correlationId = correlationId;
    }

    public DecisionContext addReason(ReasonCode code) { reasons.add(code); return this; }
    public DecisionContext consulted(String source) { if (source != null) sources.add(source); return this; }
    public DecisionContext consulted(TrustedFact<?> fact) { if (fact != null) sources.add(fact.source()); return this; }
    public DecisionContext withConfidence(double c) { this.confidence = c; return this; }
    public DecisionContext markFallback() { this.fallbackUsed = true; return this; }
    public DecisionContext markDegraded() { this.degraded = true; return this; }

    public DecisionMetadata seal() {
        long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        return DecisionMetadata.of(
                agent, agentVersion, latencyMs, confidence,
                reasons, new ArrayList<>(sources),
                fallbackUsed, degraded, correlationId);
    }
}
