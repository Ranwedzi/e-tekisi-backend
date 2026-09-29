package com.etekisi.etekisi.agent.listener;

import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DriverLocationThrottlerTest {

    private AgentProperties props;
    private DriverLocationThrottler throttler;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        props.getThrottle().setLocationMinIntervalSeconds(5);
        props.getThrottle().setLocationMinMovementMeters(50.0);
        throttler = new DriverLocationThrottler(props);
    }

    @Test
    void firstEventForDriverAlwaysEvaluates() {
        boolean ok = throttler.shouldEvaluate(1L, -26.2, 28.0, Instant.parse("2026-01-01T08:00:00Z"));
        assertThat(ok).isTrue();
    }

    @Test
    void rapidStationaryEventsAreThrottled() {
        Instant t0 = Instant.parse("2026-01-01T08:00:00Z");
        throttler.shouldEvaluate(1L, -26.2, 28.0, t0);
        // 1 second later, same position -> suppressed
        boolean ok = throttler.shouldEvaluate(1L, -26.2, 28.0, t0.plusSeconds(1));
        assertThat(ok).isFalse();
    }

    @Test
    void enoughTimeElapsedReleasesThrottle() {
        Instant t0 = Instant.parse("2026-01-01T08:00:00Z");
        throttler.shouldEvaluate(1L, -26.2, 28.0, t0);
        boolean ok = throttler.shouldEvaluate(1L, -26.2, 28.0, t0.plusSeconds(10));
        assertThat(ok).isTrue();
    }

    @Test
    void enoughMovementReleasesThrottleEvenWhenIntervalShort() {
        // Anti-safety-loss: a moving driver during a quiet window must still
        // trigger evaluation even if seconds-elapsed is below the interval.
        Instant t0 = Instant.parse("2026-01-01T08:00:00Z");
        throttler.shouldEvaluate(1L, -26.2000, 28.0000, t0);
        // ~140 metres south, 1 second later -> not throttled (movement > 50m)
        boolean ok = throttler.shouldEvaluate(1L, -26.2013, 28.0000, t0.plusSeconds(1));
        assertThat(ok).isTrue();
    }

    @Test
    void zeroConfigDisablesThrottlingEntirely() {
        props.getThrottle().setLocationMinIntervalSeconds(0);
        props.getThrottle().setLocationMinMovementMeters(0.0);
        DriverLocationThrottler open = new DriverLocationThrottler(props);
        Instant t = Instant.parse("2026-01-01T08:00:00Z");
        assertThat(open.shouldEvaluate(1L, -26.2, 28.0, t)).isTrue();
        assertThat(open.shouldEvaluate(1L, -26.2, 28.0, t)).isTrue();
        assertThat(open.shouldEvaluate(1L, -26.2, 28.0, t)).isTrue();
    }
}
