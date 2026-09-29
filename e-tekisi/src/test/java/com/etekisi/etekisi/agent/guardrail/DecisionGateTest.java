package com.etekisi.etekisi.agent.guardrail;

import com.etekisi.etekisi.agent.dto.DecisionMetadata;
import com.etekisi.etekisi.agent.dto.ReasonCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionGateTest {

    private final DecisionGate gate = new DecisionGate();

    @Test
    void rejectsNullMetadata() {
        assertThat(gate.isActionable(null, 0.5)).isFalse();
    }

    @Test
    void rejectsUnknownConfidence() {
        DecisionMetadata md = meta(-1.0, false);
        assertThat(gate.isActionable(md, 0.0)).isFalse();
    }

    @Test
    void rejectsDegradedEvenIfConfidenceHigh() {
        DecisionMetadata md = meta(0.99, true);
        assertThat(gate.isActionable(md, 0.5)).isFalse();
    }

    @Test
    void rejectsBelowThreshold() {
        DecisionMetadata md = meta(0.4, false);
        assertThat(gate.isActionable(md, 0.5)).isFalse();
    }

    @Test
    void acceptsAtThreshold() {
        DecisionMetadata md = meta(0.5, false);
        assertThat(gate.isActionable(md, 0.5)).isTrue();
    }

    private DecisionMetadata meta(double confidence, boolean degraded) {
        return DecisionMetadata.of("test", "1.0", 1L, confidence,
                List.of(ReasonCode.OK), List.of("test-source"),
                false, degraded, "corr-1");
    }
}
