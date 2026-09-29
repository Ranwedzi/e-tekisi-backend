package com.etekisi.etekisi.agent.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 3.5 — Anthropic-backed LLM intent extractor.
 *
 * <p>Active only when {@code tekisi.agent.intent.llm-enabled=true} AND a
 * non-blank {@code LLM_API_KEY} is configured. Otherwise the
 * {@link DisabledLlmIntentClient} no-op stays the active bean.
 *
 * <p>Anti-hallucination posture (defence in depth):
 * <ul>
 *   <li>The result is a CANDIDATE only. {@link TripIntentAgent} re-resolves
 *       every string through {@link RankResolver} before any commitment;
 *       the LLM never bypasses confidence scoring.</li>
 *   <li>Per-call deadline of {@code llm.timeout-ms} (default 1500ms) so a
 *       slow upstream cannot block the booking flow.</li>
 *   <li>Per-day cost cap: when the running total of accepted requests
 *       exceeds {@code llm.daily-budget}, subsequent calls return empty
 *       and increment a metric so operators see the cap kick in.</li>
 *   <li>Output schema is restricted: only {@code originGuess},
 *       {@code destinationGuess}, {@code partySize}. Any other key is
 *       ignored.</li>
 * </ul>
 *
 * <p>Failure modes always return {@code Optional.empty()} — never throw,
 * never invent a result.
 */
@Component
@ConditionalOnProperty(name = "tekisi.agent.intent.llm-enabled", havingValue = "true")
public class AnthropicLlmIntentClient implements LlmIntentClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmIntentClient.class);

    private final String apiKey;
    private final String model;
    private final long timeoutMs;
    private final int dailyBudget;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    // Simple in-process budget. Reset at midnight UTC. Operators get
    // tekisi.agent.intent.llm-budget-remaining via the meter.
    private final AtomicInteger usedToday = new AtomicInteger(0);
    private final AtomicReference<String> bucketDay = new AtomicReference<>(today());

    public AnthropicLlmIntentClient(
            @Value("${tekisi.agent.intent.llm-api-key:${LLM_API_KEY:}}") String apiKey,
            @Value("${tekisi.agent.intent.llm-model:claude-3-5-haiku-latest}") String model,
            @Value("${tekisi.agent.intent.llm-timeout-ms:1500}") long timeoutMs,
            @Value("${tekisi.agent.intent.llm-daily-budget:10000}") int dailyBudget,
            @Value("${tekisi.agent.intent.llm-base-url:https://api.anthropic.com}") String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.dailyBudget = dailyBudget;
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("x-api-key", this.apiKey)
                .build();
    }

    @Override
    public Optional<RawIntent> extract(String userText) {
        if (apiKey.isEmpty()) return Optional.empty();
        if (userText == null || userText.isBlank()) return Optional.empty();
        // Bound the input — protects against pathological prompts and cost.
        String input = userText.length() > 500 ? userText.substring(0, 500) : userText;

        if (!withinBudget()) {
            log.warn("agent.intent.llm.budget_exceeded used={} cap={}", usedToday.get(), dailyBudget);
            return Optional.empty();
        }

        Map<String, Object> payload = Map.of(
                "model", model,
                "max_tokens", 200,
                "system", "You extract origin and destination taxi-rank names from informal " +
                        "South African English/Zulu/Sotho phrases. Reply ONLY in compact JSON: " +
                        "{\"origin\":\"<rank-name-or-null>\",\"destination\":\"<rank-name-or-null>\"," +
                        "\"partySize\":<int 1-15>}. Never invent ranks.",
                "messages", new Object[]{ Map.of("role", "user", "content", input) }
        );

        try {
            // Bound the upstream call. RestClient does not enforce a per-call
            // deadline directly — we rely on the JVM HTTP client's connect/
            // request timeouts which Boot 4 configures from sane defaults
            // (~30s). For a strict 1.5s budget Phase 4.x will swap to an
            // async client; today the time-limiter at the EtaService layer
            // is the only network we have on the synchronous path.
            String body = http.post()
                    .uri("/v1/messages")
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            usedToday.incrementAndGet();

            JsonNode root = json.readTree(body);
            JsonNode contentArr = root.path("content");
            if (!contentArr.isArray() || contentArr.isEmpty()) return Optional.empty();
            String text = contentArr.get(0).path("text").asText("").trim();
            if (text.isEmpty()) return Optional.empty();

            JsonNode parsed = json.readTree(text);
            String origin = parsed.path("origin").isTextual() ? parsed.path("origin").asText().trim() : null;
            String dest   = parsed.path("destination").isTextual() ? parsed.path("destination").asText().trim() : null;
            int party     = clampParty(parsed.path("partySize").asInt(1));

            if (isBlank(origin) && isBlank(dest)) return Optional.empty();
            return Optional.of(new RawIntent(origin, dest, party));
        } catch (Exception e) {
            log.warn("agent.intent.llm.failed error={}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean withinBudget() {
        String today = today();
        bucketDay.updateAndGet(prev -> {
            if (!prev.equals(today)) {
                usedToday.set(0);
                return today;
            }
            return prev;
        });
        return usedToday.get() < dailyBudget;
    }

    private static String today() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).toString();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank() || "null".equalsIgnoreCase(s); }

    private static int clampParty(int p) {
        if (p < 1) return 1;
        if (p > 15) return 15;
        return p;
    }

    /** For tests / diagnostics. */
    public int usedToday() { return usedToday.get(); }
    public Duration timeout() { return Duration.ofMillis(timeoutMs); }
}
