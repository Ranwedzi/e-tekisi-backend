package com.etekisi.etekisi.agent.eta;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Phase 2.1 — Resilience4j wiring for the external routing API.
 *
 * <p>Three patterns, composed inside {@link ExternalRoutingEtaProvider}:
 * <ul>
 *   <li><b>TimeLimiter</b> — per-call deadline so a slow upstream never
 *       blocks the booking hot-path beyond {@code routing.timeout-ms}.</li>
 *   <li><b>Retry</b> — bounded exponential backoff, only on transient
 *       failures. Idempotent GET so retry is safe.</li>
 *   <li><b>CircuitBreaker</b> — opens after sustained failure so the
 *       provider chain falls through to heuristic immediately, without
 *       paying the timeout cost on every request.</li>
 * </ul>
 *
 * <p>All three are exposed as Spring beans rather than annotations because
 * we apply them programmatically — the provider must continue to return
 * {@code Optional.empty()} on failure (the EtaService contract), not throw,
 * so we cannot rely on a fallback method alone.
 *
 * <p>When Micrometer is on the classpath the breaker's state transitions
 * are bound to {@code resilience4j.circuitbreaker.*} metrics for the
 * Phase 2.5 alert {@code routingApi circuit breaker open > 1m}.
 */
@Configuration
public class RoutingResilience {

    public static final String NAME = "routingApi";

    @Bean
    public CircuitBreaker routingCircuitBreaker(
            @Value("${tekisi.agent.eta.routing.circuit.failure-rate-threshold:50}") float failureRate,
            @Value("${tekisi.agent.eta.routing.circuit.minimum-calls:10}") int minCalls,
            @Value("${tekisi.agent.eta.routing.circuit.wait-duration-ms:30000}") long waitMs,
            @Value("${tekisi.agent.eta.routing.circuit.sliding-window:20}") int slidingWindow,
            ObjectProvider<MeterRegistry> meterRegistry) {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRate)
                .slidingWindowSize(slidingWindow)
                .minimumNumberOfCalls(minCalls)
                .waitDurationInOpenState(Duration.ofMillis(waitMs))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(RuntimeException.class)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        CircuitBreaker breaker = registry.circuitBreaker(NAME);

        MeterRegistry mr = meterRegistry.getIfAvailable();
        if (mr != null) {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(mr);
        }
        return breaker;
    }

    @Bean
    public Retry routingRetry(
            @Value("${tekisi.agent.eta.routing.retry.max-attempts:2}") int maxAttempts,
            @Value("${tekisi.agent.eta.routing.retry.wait-ms:200}") long waitMs) {

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(waitMs))
                .retryExceptions(RuntimeException.class)
                .build();
        return Retry.of(NAME, config);
    }

    @Bean
    public TimeLimiter routingTimeLimiter(
            @Value("${tekisi.agent.eta.routing.timeout-ms:800}") long timeoutMs) {

        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(timeoutMs))
                .cancelRunningFuture(true)
                .build();
        return TimeLimiter.of(NAME, config);
    }
}
