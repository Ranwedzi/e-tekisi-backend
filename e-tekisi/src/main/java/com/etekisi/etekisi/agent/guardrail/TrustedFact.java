package com.etekisi.etekisi.agent.guardrail;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Wraps any value retrieved from an authoritative source with the
 * timestamp it was fetched at and a short label of where it came from.
 *
 * Anti-hallucination rule: agents may only branch on TrustedFact instances.
 * If {@link #isFresh(Duration)} returns false, the value is treated as
 * unknown and the deterministic fallback path must be taken.
 */
public record TrustedFact<T>(T value, Instant fetchedAt, String source) {

    public static <T> TrustedFact<T> of(T value, String source) {
        return new TrustedFact<>(value, Instant.now(), source);
    }

    public static <T> TrustedFact<T> missing(String source) {
        return new TrustedFact<>(null, Instant.now(), source);
    }

    public boolean isPresent() { return value != null; }

    public boolean isFresh(Duration ttl) {
        return value != null && Duration.between(fetchedAt, Instant.now()).compareTo(ttl) <= 0;
    }

    public Optional<T> asOptional() { return Optional.ofNullable(value); }
}
