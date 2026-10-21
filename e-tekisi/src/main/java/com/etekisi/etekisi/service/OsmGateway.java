package com.etekisi.etekisi.service;

import com.etekisi.etekisi.cluster.SharedState;
import com.etekisi.etekisi.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One place for every call to the OpenStreetMap services (Nominatim
 * geocoding, OSRM routing, Overpass rank discovery).
 *
 * <ul>
 *   <li><b>Configurable base URLs</b> so production can point at self-hosted
 *       instances: the public servers are for light use only.</li>
 *   <li><b>At most one request per second to the public servers</b>, as
 *       their usage policies require. A caller that would have to wait too
 *       long gets 503 instead of piling up threads. Self-hosted instances
 *       (docker-compose.maps.yml) aren't limited.</li>
 *   <li><b>Response caching</b>, so repeated lookups (the same rank, the same
 *       trip every 5 s of polling) don't hit the upstream at all.</li>
 * </ul>
 *
 * With several backend instances (Redis, see {@link SharedState}) the
 * one-per-second slots and the cache are shared, so the limit holds for the
 * whole deployment, not per instance.
 *
 * Returns the raw response body; callers parse it.
 */
@Service
public class OsmGateway {

    private static final Logger log = LoggerFactory.getLogger(OsmGateway.class);
    private static final Duration MAX_QUEUE_WAIT = Duration.ofSeconds(3);

    public enum Upstream { NOMINATIM, OSRM, OVERPASS }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final String nominatimUrl;
    private final String osrmUrl;
    private final String overpassUrl;
    private final String userAgent;
    private final SharedState state;
    /** The services pointed at the public OpenStreetMap servers, which allow about 1 request/second. */
    private final java.util.Set<Upstream> publicServers = java.util.EnumSet.noneOf(Upstream.class);

    private final Map<Upstream, MinIntervalLimiter> limiters = Map.of(
            Upstream.NOMINATIM, new MinIntervalLimiter(Duration.ofSeconds(1)),
            Upstream.OSRM, new MinIntervalLimiter(Duration.ofSeconds(1)),
            Upstream.OVERPASS, new MinIntervalLimiter(Duration.ofSeconds(1)));

    private final TtlCache geocodeCache = new TtlCache(Duration.ofHours(24), 5_000);
    private final TtlCache routeCache = new TtlCache(Duration.ofHours(1), 2_000);
    private final TtlCache overpassCache = new TtlCache(Duration.ofMinutes(1), 500);

    public OsmGateway(@Value("${tekisi.osm.nominatim-url:https://nominatim.openstreetmap.org}") String nominatimUrl,
                      @Value("${tekisi.osm.osrm-url:https://router.project-osrm.org}") String osrmUrl,
                      @Value("${tekisi.osm.overpass-url:https://overpass-api.de/api/interpreter}") String overpassUrl,
                      @Value("${tekisi.osm.user-agent:e-tekisi/1.0}") String userAgent,
                      SharedState state) {
        this.state = state;
        this.nominatimUrl = stripSlash(nominatimUrl);
        this.osrmUrl = stripSlash(osrmUrl);
        this.overpassUrl = overpassUrl;
        this.userAgent = userAgent;
        if (isPublicServer(nominatimUrl)) publicServers.add(Upstream.NOMINATIM);
        if (isPublicServer(osrmUrl)) publicServers.add(Upstream.OSRM);
        if (isPublicServer(overpassUrl)) publicServers.add(Upstream.OVERPASS);
        log.info("osm.services nominatim={} osrm={} overpass={} rateLimited={}",
                this.nominatimUrl, this.osrmUrl, this.overpassUrl, publicServers);
    }

    static boolean isPublicServer(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return true;
        }
        if (host == null) return true;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("openstreetmap.org") || host.endsWith(".openstreetmap.org")
                || host.equals("router.project-osrm.org")
                || host.equals("overpass-api.de") || host.endsWith(".overpass-api.de")
                || host.endsWith(".kumi.systems") || host.endsWith(".openstreetmap.fr");
    }

    /** Nominatim search, first match only. */
    public String geocode(String query) {
        String key = query.trim().toLowerCase(Locale.ROOT);
        return cached("geocode", geocodeCache, key, () -> send(Upstream.NOMINATIM, HttpRequest.newBuilder()
                .uri(URI.create(nominatimUrl + "/search?format=json&limit=1&q="
                        + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)))
                .GET()));
    }

    /** OSRM driving route with full GeoJSON geometry. */
    public String route(double fromLat, double fromLng, double toLat, double toLng) {
        // ~11 m grid, so tiny GPS jitter reuses the cached route.
        String key = String.format(Locale.ROOT, "%.4f,%.4f;%.4f,%.4f", fromLat, fromLng, toLat, toLng);
        return cached("route", routeCache, key, () -> send(Upstream.OSRM, HttpRequest.newBuilder()
                .uri(URI.create(String.format(Locale.ROOT,
                        "%s/route/v1/driving/%s,%s;%s,%s?overview=full&geometries=geojson",
                        osrmUrl, fromLng, fromLat, toLng, toLat)))
                .GET()));
    }

    /** Overpass QL query, cached by {@code cacheKey}. */
    public String overpass(String cacheKey, String query) {
        return cached("overpass", overpassCache, cacheKey, () -> send(Upstream.OVERPASS, HttpRequest.newBuilder()
                .uri(URI.create(overpassUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))));
    }

    /** The local cache, or with shared state the deployment-wide one (same TTL). */
    private String cached(String kind, TtlCache local, String key, java.util.function.Supplier<String> loader) {
        if (!state.shared()) return local.getOrLoad(key, loader);
        String k = "osm:cache:" + kind + ":" + sha256(key);
        return state.get(k).orElseGet(() -> {
            String v = loader.get();
            state.put(k, v, local.ttl());
            return v;
        });
    }

    /**
     * One request per second per service across every instance: each caller
     * claims the next free one-second slot (up to {@link #MAX_QUEUE_WAIT} ahead)
     * and waits for it.
     */
    private boolean acquireSharedSlot(Upstream upstream) {
        long nowMs = System.currentTimeMillis();
        long first = nowMs / 1000;
        for (long sec = first; sec <= first + MAX_QUEUE_WAIT.toSeconds(); sec++) {
            if (state.putIfAbsent("osm:slot:" + upstream + ":" + sec, "1", Duration.ofSeconds(10))) {
                long wait = sec * 1000 - System.currentTimeMillis();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String send(Upstream upstream, HttpRequest.Builder request) {
        boolean slot = !publicServers.contains(upstream)
                || (state.shared() ? acquireSharedSlot(upstream) : limiters.get(upstream).acquire(MAX_QUEUE_WAIT));
        if (!slot) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_BUSY",
                    "Map service is busy. Try again in a moment.", null);
        }
        try {
            HttpResponse<String> response = http.send(request
                            .timeout(Duration.ofSeconds(12))
                            .header("User-Agent", userAgent)
                            .header("Accept", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("osm.upstream.status upstream={} status={}", upstream, response.statusCode());
                throw new UpstreamException(upstream + " returned " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            log.warn("osm.upstream.io upstream={} error={}", upstream, e.getMessage());
            throw new UpstreamException(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("interrupted");
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The upstream failed or returned non-200. Callers turn this into 502. */
    public static class UpstreamException extends RuntimeException {
        public UpstreamException(String message) { super(message); }
    }

    /** Hands out one slot per interval; refuses callers who'd wait longer than {@code maxWait}. */
    static final class MinIntervalLimiter {
        private final long intervalNanos;
        private long nextFree = System.nanoTime();

        MinIntervalLimiter(Duration interval) {
            this.intervalNanos = interval.toNanos();
        }

        boolean acquire(Duration maxWait) {
            long waitNanos;
            synchronized (this) {
                long now = System.nanoTime();
                long slot = Math.max(now, nextFree);
                waitNanos = slot - now;
                if (waitNanos > maxWait.toNanos()) return false;
                nextFree = slot + intervalNanos;
            }
            if (waitNanos > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(waitNanos));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** Thread-safe, size-capped cache with a fixed time-to-live per entry. */
    static final class TtlCache {
        private record Entry(String value, long expiresAtMillis) {}

        private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
        private final long ttlMillis;
        private final int maxSize;

        TtlCache(Duration ttl, int maxSize) {
            this.ttlMillis = ttl.toMillis();
            this.maxSize = maxSize;
        }

        Duration ttl() {
            return Duration.ofMillis(ttlMillis);
        }

        String getOrLoad(String key, java.util.function.Supplier<String> loader) {
            long now = System.currentTimeMillis();
            Entry e = map.get(key);
            if (e != null && e.expiresAtMillis() > now) return e.value();
            String value = loader.get();
            if (map.size() >= maxSize) evict(now);
            map.put(key, new Entry(value, now + ttlMillis));
            return value;
        }

        private void evict(long now) {
            map.entrySet().removeIf(en -> en.getValue().expiresAtMillis() <= now);
            // Still full: drop an arbitrary tenth rather than grow without bound.
            Iterator<String> it = map.keySet().iterator();
            for (int dropped = 0; map.size() >= maxSize && it.hasNext() && dropped < Math.max(1, maxSize / 10); dropped++) {
                it.next();
                it.remove();
            }
        }
    }
}
