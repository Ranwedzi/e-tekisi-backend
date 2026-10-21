package com.etekisi.etekisi.service;

import com.etekisi.etekisi.cluster.MemorySharedState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

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

    @Test
    void onlyThePublicServersAreRateLimited() {
        assertThat(OsmGateway.isPublicServer("https://nominatim.openstreetmap.org")).isTrue();
        assertThat(OsmGateway.isPublicServer("https://router.project-osrm.org")).isTrue();
        assertThat(OsmGateway.isPublicServer("https://overpass-api.de/api/interpreter")).isTrue();
        assertThat(OsmGateway.isPublicServer("http://osrm:5000")).isFalse();
        assertThat(OsmGateway.isPublicServer("http://nominatim:8080")).isFalse();
        assertThat(OsmGateway.isPublicServer("https://maps.tekisi.example.co.za/overpass/api/interpreter")).isFalse();
    }

    @Test
    void aSelfHostedRouterIsCalledAsFastAsItAnswers() throws Exception {
        HttpServer osrm = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        osrm.createContext("/route/", ex -> {
            calls.incrementAndGet();
            byte[] body = "{\"code\":\"Ok\"}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        osrm.start();
        try {
            String base = "http://localhost:" + osrm.getAddress().getPort();
            OsmGateway gateway = new OsmGateway(base, base, base + "/api/interpreter", "test", new MemorySharedState());
            long start = System.nanoTime();
            for (int i = 0; i < 8; i++) gateway.route(-26.2 + i * 0.01, 28.0, -26.3, 28.1); // distinct: no cache hits
            assertThat(calls.get()).isEqualTo(8);
            // Throttled, eight calls would take at least seven seconds.
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        } finally {
            osrm.stop(0);
        }
    }
}
