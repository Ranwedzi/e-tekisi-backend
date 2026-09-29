package com.etekisi.etekisi.agent.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceLevelTest {

    @Test
    void unknownForNegativeOrNaN() {
        assertThat(ConfidenceLevel.fromScore(-0.1)).isEqualTo(ConfidenceLevel.UNKNOWN);
        assertThat(ConfidenceLevel.fromScore(Double.NaN)).isEqualTo(ConfidenceLevel.UNKNOWN);
    }

    @Test
    void bandsAreInclusiveOfTheirThresholds() {
        assertThat(ConfidenceLevel.fromScore(0.0)).isEqualTo(ConfidenceLevel.LOW);
        assertThat(ConfidenceLevel.fromScore(0.49)).isEqualTo(ConfidenceLevel.LOW);
        assertThat(ConfidenceLevel.fromScore(0.50)).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(ConfidenceLevel.fromScore(0.79)).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(ConfidenceLevel.fromScore(0.80)).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(ConfidenceLevel.fromScore(1.0)).isEqualTo(ConfidenceLevel.HIGH);
    }
}
