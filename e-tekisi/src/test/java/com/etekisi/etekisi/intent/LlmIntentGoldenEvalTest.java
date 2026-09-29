package com.etekisi.etekisi.intent;

import com.etekisi.etekisi.agent.intent.LlmIntentClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.5 — golden-eval test. Reads {@code intent/golden.csv} and runs each
 * utterance through a configured {@link LlmIntentClient}. Asserts that the
 * extracted origin / destination contains the expected token (case-insensitive).
 *
 * <p>Disabled by default — runs in CI nightly via the env var
 * {@code TEKISI_RUN_LLM_GOLDEN=1}. Local devs do not need an API key.
 *
 * <p>Threshold: 95% of expectations must pass. The roadmap allows the
 * production flag flip only once this threshold holds for 7 consecutive
 * nights.
 */
@EnabledIfEnvironmentVariable(named = "TEKISI_RUN_LLM_GOLDEN", matches = "1")
class LlmIntentGoldenEvalTest {

    private static final double THRESHOLD = 0.95;

    @Test
    void llmExtractionMeetsGoldenThreshold() throws Exception {
        // Caller must wire a real LlmIntentClient bean (e.g. AnthropicLlmIntentClient)
        // and pass the API key via env. We fail closed if the bean is missing.
        LlmIntentClient client = clientFromSpi();
        assertThat(client)
                .as("LLM client SPI implementation must be on the test classpath")
                .isNotNull();

        List<Row> rows = loadGolden();
        assertThat(rows).isNotEmpty();

        int totalExpectations = 0;
        int passed = 0;
        List<String> failures = new ArrayList<>();

        for (Row r : rows) {
            Optional<LlmIntentClient.RawIntent> out = client.extract(r.utterance);
            String origin = out.map(LlmIntentClient.RawIntent::originGuess).orElse("");
            String dest = out.map(LlmIntentClient.RawIntent::destinationGuess).orElse("");

            if (!"-".equals(r.expectedOrigin)) {
                totalExpectations++;
                if (containsCi(origin, r.expectedOrigin)) passed++;
                else failures.add("ORIGIN expected=" + r.expectedOrigin + " got=" + origin
                        + " utterance=" + r.utterance);
            }
            if (!"-".equals(r.expectedDestination)) {
                totalExpectations++;
                if (containsCi(dest, r.expectedDestination)) passed++;
                else failures.add("DEST expected=" + r.expectedDestination + " got=" + dest
                        + " utterance=" + r.utterance);
            }
        }

        double rate = totalExpectations == 0 ? 0.0 : (double) passed / totalExpectations;
        System.out.printf("golden-eval: %d/%d (%.1f%%)%n", passed, totalExpectations, rate * 100);
        if (!failures.isEmpty()) {
            failures.stream().limit(20).forEach(System.out::println);
        }
        assertThat(rate)
                .as("LLM intent golden-eval pass rate (target ≥ %s)", THRESHOLD)
                .isGreaterThanOrEqualTo(THRESHOLD);
    }

    private static boolean containsCi(String haystack, String needle) {
        if (haystack == null) return false;
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }

    private static List<Row> loadGolden() throws Exception {
        try (InputStream in = LlmIntentGoldenEvalTest.class.getResourceAsStream("/intent/golden.csv")) {
            assertThat(in).as("golden.csv must be on the test classpath").isNotNull();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<Row> out = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\|");
                if (parts.length < 3) continue;
                out.add(new Row(parts[0].trim(), parts[1].trim(), parts[2].trim()));
            }
            return out;
        }
    }

    /**
     * Resolve via java.util.ServiceLoader so the test does not require a
     * Spring context. CI wires an entry under
     * {@code META-INF/services/com.etekisi.etekisi.agent.intent.LlmIntentClient}
     * pointing at the configured implementation (Anthropic in prod, a stub
     * in dry-run).
     */
    private static LlmIntentClient clientFromSpi() {
        try {
            return java.util.ServiceLoader.load(LlmIntentClient.class)
                    .findFirst()
                    .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private record Row(String utterance, String expectedOrigin, String expectedDestination) {}
}
