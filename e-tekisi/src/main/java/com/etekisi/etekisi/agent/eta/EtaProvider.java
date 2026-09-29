package com.etekisi.etekisi.agent.eta;

import java.util.Optional;

/**
 * Provider abstraction for ETA / route-aware time estimation.
 *
 * Multiple providers can be wired (e.g. an external routing API and a
 * heuristic). {@link EtaService} consults them in priority order; on failure
 * or absent quote the next provider is tried, and the heuristic is the
 * always-on fallback.
 *
 * Anti-hallucination contract:
 *   - {@link #quote(double, double, double, double)} returns Optional.empty()
 *     on any kind of failure or out-of-distribution input — never a guess.
 *   - returned {@link RouteQuote} is bounded (see record invariants).
 *   - implementations must NOT throw out of {@code quote()}; failures must
 *     reduce to Optional.empty() so the service can degrade cleanly.
 */
public interface EtaProvider {

    /** Stable provider name used in metrics, audit, and logs. */
    String name();

    /** Higher = preferred. Heuristic fallback should be the lowest. */
    int priority();

    /**
     * Produce a bounded quote from origin to destination.
     * Returning Optional.empty() means "I cannot answer with confidence" —
     * never a guess, never a near-zero placeholder.
     */
    Optional<RouteQuote> quote(double originLat, double originLng,
                               double destLat, double destLng);
}
