package com.etekisi.etekisi.agent.intent;

import java.util.Optional;

/**
 * Minimal seam for an LLM-backed intent extractor.
 *
 * The default implementation is {@link DisabledLlmIntentClient}, which
 * returns {@link Optional#empty()} — keeping the system fully deterministic
 * out of the box. A real implementation (Anthropic SDK) wires in later as
 * a Spring bean, gated by tekisi.agent.intent.llm-enabled = true.
 */
public interface LlmIntentClient {

    /**
     * Extract candidate origin/destination strings from raw user text.
     * Returns empty when the LLM is disabled or fails. The caller MUST
     * still resolve every returned string against the rank repository.
     */
    Optional<RawIntent> extract(String userText);

    record RawIntent(String originGuess, String destinationGuess, int partySize) {}
}
