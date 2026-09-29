package com.etekisi.etekisi.agent.eta;

import com.etekisi.etekisi.agent.dto.EtaEstimate;
import com.etekisi.etekisi.config.SloMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import java.time.Duration;
import java.util.Optional;

/**
 * External HTTP routing-API ETA provider — designed for OSRM-style responses
 * but behind a thin parser so other providers (Mapbox, GraphHopper, Google)
 * can be plugged in by adapting parsing only.
 *
 * Activation:
 *   - bean exists only when {@code tekisi.agent.eta.routing.enabled=true}
 *     AND a non-blank base-url is configured
 *
 * Anti-hallucination posture:
 *   - any HTTP failure, parse failure, timeout, or malformed response =&gt;
 *     {@code Optional.empty()}; the EtaService falls back cleanly
 *   - never throws out of {@link #quote(double, double, double, double)}
 *   - never returns a default/zero quote on failure
 *   - confidence is 0.85 by default for routing-API answers (high but not
 *     unconditional — providers can disagree with reality)
 */
@Component
@ConditionalOnProperty(name = "tekisi.agent.eta.routing.enabled", havingValue = "true")
public class ExternalRoutingEtaProvider implements EtaProvider {

    public static final String PROVIDER_NAME = "external-routing";
    private static final Logger log = LoggerFactory.getLogger(ExternalRoutingEtaProvider.class);

    private final RoutingProperties config;
    private final RestClient httpClient;
    private final ObjectMapper json = new ObjectMapper();

    // Field-injected to keep the existing single-arg constructor test-compatible.
    @Autowired(required = false)
    private ObjectProvider<SloMetrics> sloProvider;

    // Phase 2.1 — wrapped programmatically so the provider keeps returning
    // Optional.empty() on failure (the EtaService contract). Field-injected
    // so EtaServiceTest's direct construction stays valid.
    @Autowired(required = false)
    private CircuitBreaker routingBreaker;

    @Autowired(required = false)
    private Retry routingRetry;

    @Autowired(required = false)
    private TimeLimiter routingTimeLimiter;

    // Bounded executor used only to back the TimeLimiter — sized small because
    // a single in-flight upstream call per request is sufficient.
    private final ScheduledExecutorService timeLimiterExecutor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "routing-tl-");
                t.setDaemon(true);
                return t;
            });

    public ExternalRoutingEtaProvider(RoutingProperties config) {
        this.config = config;
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            log.warn("agent.eta.routing.misconfigured base-url is blank — provider will refuse to quote");
        }
        this.httpClient = RestClient.builder()
                .baseUrl(config.getBaseUrl() == null ? "" : config.getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String name() { return PROVIDER_NAME; }

    @Override
    public int priority() { return 100; } // preferred over heuristic

    @Override
    public Optional<RouteQuote> quote(double originLat, double originLng,
                                      double destLat, double destLng) {
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            return Optional.empty();
        }
        Supplier<Optional<RouteQuote>> raw = () -> doQuote(originLat, originLng, destLat, destLng);

        Supplier<Optional<RouteQuote>> guarded = raw;
        if (routingBreaker != null) {
            guarded = CircuitBreaker.decorateSupplier(routingBreaker, guarded);
        }
        if (routingRetry != null) {
            guarded = Retry.decorateSupplier(routingRetry, guarded);
        }

        try {
            if (routingTimeLimiter != null) {
                Supplier<Optional<RouteQuote>> finalGuarded = guarded;
                CompletableFuture<Optional<RouteQuote>> fut =
                        CompletableFuture.supplyAsync(finalGuarded, timeLimiterExecutor);
                return routingTimeLimiter.executeFutureSupplier(() -> fut);
            }
            return guarded.get();
        } catch (CallNotPermittedException breakerOpen) {
            // Circuit breaker is open — fail fast, don't pay the timeout. Don't
            // log on every call (it's expected during the open window) but do
            // count in metrics.
            emitRoutingFailureMetric();
            return Optional.empty();
        } catch (TimeoutException te) {
            log.warn("agent.eta.routing.timeout provider={}", PROVIDER_NAME);
            emitRoutingFailureMetric();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("agent.eta.routing.failed provider={} error={}", PROVIDER_NAME, e.getMessage());
            emitRoutingFailureMetric();
            return Optional.empty();
        }
    }

    /** Single uninstrumented HTTP attempt; lifted so resilience decorators can wrap it. */
    private Optional<RouteQuote> doQuote(double originLat, double originLng,
                                         double destLat, double destLng) {
        // OSRM-style URL: /route/v1/driving/{lng,lat;lng,lat}?overview=false
        String coords = originLng + "," + originLat + ";" + destLng + "," + destLat;
        String path = "/route/v1/driving/" + coords + "?overview=false&alternatives=false&steps=false";
        String body = httpClient
                .get()
                .uri(path)
                .retrieve()
                .body(String.class);
        return parse(body);
    }

    private void emitRoutingFailureMetric() {
        // Heuristic: treat *any* failure shape as a routing-timeout signal for
        // the SLO. The dashboard cares about external-routing unavailability,
        // not about which TCP layer reported it.
        if (sloProvider != null) {
            SloMetrics m = sloProvider.getIfAvailable();
            if (m != null) m.routingTimeout();
        }
    }

    /** Visible for test. Parses OSRM-style response. */
    Optional<RouteQuote> parse(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode root = json.readTree(body);
            JsonNode routes = root.path("routes");
            if (!routes.isArray() || routes.isEmpty()) return Optional.empty();
            JsonNode first = routes.get(0);
            double durationSec = first.path("duration").asDouble(-1.0);
            double distanceMeters = first.path("distance").asDouble(-1.0);
            if (durationSec <= 0 || distanceMeters < 0) return Optional.empty();

            int meanSeconds = (int) Math.round(durationSec);
            // p90 buffer of 25% — routing APIs are already traffic-aware but
            // South African minibus dwell is unmodelled, so we pad explicitly.
            int p90Seconds = (int) Math.round(meanSeconds * 1.25);
            double distanceKm = round(distanceMeters / 1000.0);
            return Optional.of(new RouteQuote(
                    meanSeconds, p90Seconds,
                    distanceKm, 0.85,
                    EtaEstimate.Source.ROUTING_API, PROVIDER_NAME));
        } catch (Exception e) {
            log.warn("agent.eta.routing.parse_failed error={}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Exposed for diagnostics. */
    public Duration timeout() { return Duration.ofMillis(config.getTimeoutMs()); }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
