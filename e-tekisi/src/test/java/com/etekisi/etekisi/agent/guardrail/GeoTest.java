package com.etekisi.etekisi.agent.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoTest {

    @Test
    void zeroDistanceForIdenticalPoints() {
        assertThat(Geo.haversineMeters(-26.2041, 28.0473, -26.2041, 28.0473))
                .isEqualTo(0.0);
    }

    @Test
    void approximatesParkStationToBaragwanath() {
        // Park Station (Joburg CBD) ~ -26.2008, 28.0440
        // Baragwanath rank ~ -26.2685, 27.9395
        double m = Geo.haversineMeters(-26.2008, 28.0440, -26.2685, 27.9395);
        // Realistically 12–15 km — assert a generous band so test stays robust.
        assertThat(m).isBetween(10_000.0, 18_000.0);
    }
}
