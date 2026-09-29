package com.etekisi.etekisi.agent.guardrail;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedFactTest {

    @Test
    void missingIsNotPresentAndNotFresh() {
        TrustedFact<String> f = TrustedFact.missing("repo");
        assertThat(f.isPresent()).isFalse();
        assertThat(f.isFresh(Duration.ofSeconds(60))).isFalse();
        assertThat(f.asOptional()).isEmpty();
    }

    @Test
    void freshNowIsFresh() {
        TrustedFact<String> f = TrustedFact.of("v", "repo");
        assertThat(f.isFresh(Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void staleFactIsNotFresh() {
        TrustedFact<String> stale = new TrustedFact<>("v",
                Instant.now().minusSeconds(120), "repo");
        assertThat(stale.isFresh(Duration.ofSeconds(30))).isFalse();
    }
}
