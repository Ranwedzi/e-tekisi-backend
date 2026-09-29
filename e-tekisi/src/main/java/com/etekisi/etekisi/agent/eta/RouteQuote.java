package com.etekisi.etekisi.agent.eta;

import com.etekisi.etekisi.agent.dto.EtaEstimate;

/**
 * Bounded quote returned by an {@link EtaProvider}.
 *
 * Anti-hallucination contract:
 *   - all fields are concrete numbers; no nulls allowed
 *   - confidence is bounded to [0.0, 1.0]
 *   - p90Seconds &gt;= meanSeconds (the provider must enforce this)
 *   - source identifies the origin so the audit trail can never be ambiguous
 */
public record RouteQuote(
        int meanSeconds,
        int p90Seconds,
        double distanceKm,
        double confidence,
        EtaEstimate.Source source,
        String providerName
) {
    public RouteQuote {
        if (meanSeconds < 0) throw new IllegalArgumentException("meanSeconds must be >= 0");
        if (p90Seconds < meanSeconds) throw new IllegalArgumentException("p90Seconds must be >= meanSeconds");
        if (distanceKm < 0) throw new IllegalArgumentException("distanceKm must be >= 0");
        if (confidence < 0.0 || confidence > 1.0)
            throw new IllegalArgumentException("confidence must be in [0,1]");
        if (source == null) throw new IllegalArgumentException("source required");
        if (providerName == null || providerName.isBlank())
            throw new IllegalArgumentException("providerName required");
    }
}
