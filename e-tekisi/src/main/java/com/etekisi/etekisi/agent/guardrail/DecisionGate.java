package com.etekisi.etekisi.agent.guardrail;

import com.etekisi.etekisi.agent.dto.ConfidenceLevel;
import com.etekisi.etekisi.agent.dto.DecisionMetadata;
import org.springframework.stereotype.Component;

/**
 * Last-mile gate before any decision is published.
 *
 * If confidence is below threshold or sources are degraded, the gate
 * returns false and the caller MUST fall back to deterministic safe logic.
 * Centralising this decision means agents cannot quietly act on weak signals.
 */
@Component
public class DecisionGate {

    public boolean isActionable(DecisionMetadata md, double minConfidence) {
        if (md == null) return false;
        if (md.confidenceLevel() == ConfidenceLevel.UNKNOWN) return false;
        if (md.degraded()) return false;
        return md.confidence() >= minConfidence;
    }
}
