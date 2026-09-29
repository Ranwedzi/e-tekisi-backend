package com.etekisi.etekisi.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OsmGatewayTest {

    @Test
    void cacheServesRepeatsWithoutReloading() {
        OsmGateway.TtlCache cache = new OsmGateway.TtlCache(Duration.ofMinutes(1), 10);
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            assertThat(cache.getOrLoad("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        }
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void cacheStaysWithinItsSizeCap() {
        OsmGateway.TtlCache cache = new OsmGateway.TtlCache(Duration.ofMinutes(1), 10);
        for (int i = 0; i < 100; i++) {
            final int n = i;
            cache.getOrLoad("k" + i, () -> "v" + n);
        }
        // Reload count proves old keys were evicted rather than kept forever.
        AtomicInteger reloads = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            cache.getOrLoad("k" + i, () -> { reloads.incrementAndGet(); return "x"; });
        }
        assertThat(reloads.get()).isGreaterThan(80);
    }

    @Test
    void expiredEntriesReload() throws Exception {
        OsmGateway.TtlCache cache = new OsmGateway.TtlCache(Duration.ofMillis(20), 10);
        cache.getOrLoad("k", () -> "old");
        Thread.sleep(40);
        assertThat(cache.getOrLoad("k", () -> "new")).isEqualTo("new");
    }

    @Test
    void limiterSpacesCallsAndRefusesLongWaits() {
        OsmGateway.MinIntervalLimiter limiter = new OsmGateway.MinIntervalLimiter(Duration.ofMillis(200));
        long start = System.nanoTime();
        assertThat(limiter.acquire(Duration.ofSeconds(1))).isTrue();
        assertThat(limiter.acquire(Duration.ofSeconds(1))).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(150));
        // Next free slot is ~200 ms away; a caller only willing to wait 10 ms is turned away.
        assertThat(limiter.acquire(Duration.ofMillis(10))).isFalse();
    }
}
