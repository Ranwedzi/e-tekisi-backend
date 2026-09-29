package com.etekisi.etekisi.agent.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Metadata that MUST accompany every agent decision.
 * Carried in the result DTO so confidence, sources, and audit trail
 * are inseparable from the answer itself.
 */
public record DecisionMetadata(
        UUID decisionId,
        String agent,
        String agentVersion,
        Instant computedAt,
        long latencyMs,
        double confidence,
        ConfidenceLevel confidenceLevel,
        List<ReasonCode> reasonCodes,
        List<String> sourcesConsulted,
        boolean fallbackUsed,
        boolean degraded,
        String correlationId
) {
    public static DecisionMetadata of(
            String agent,
            String agentVersion,
            long latencyMs,
            double confidence,
            List<ReasonCode> reasonCodes,
            List<String> sourcesConsulted,
            boolean fallbackUsed,
            boolean degraded,
            String correlationId) {
        return new DecisionMetadata(
                UUID.randomUUID(),
                agent,
                agentVersion,
                Instant.now(),
                latencyMs,
                confidence,
                ConfidenceLevel.fromScore(confidence),
                List.copyOf(reasonCodes),
                List.copyOf(sourcesConsulted),
                fallbackUsed,
                degraded,
                correlationId
        );
    }
}
