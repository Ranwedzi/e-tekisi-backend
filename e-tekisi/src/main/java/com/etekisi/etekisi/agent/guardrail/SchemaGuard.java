package com.etekisi.etekisi.agent.guardrail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Strict JSON parser used to validate untrusted (LLM) outputs before they
 * reach any decision path. Unknown fields → reject. Malformed JSON → reject.
 *
 * Agents MUST route LLM responses through this guard.
 */
@Component
public class SchemaGuard {

    private final ObjectMapper strict;

    public SchemaGuard() {
        this.strict = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
    }

    public <T> Optional<T> parse(String json, Class<T> type) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(strict.readValue(json, type));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
