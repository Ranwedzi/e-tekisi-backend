package com.etekisi.etekisi.agent.dto;

import java.util.List;

/**
 * Output of the Trip Intent agent. The LLM never produces final IDs —
 * it produces candidate strings that are resolved against repositories.
 * If resolution fails, status is NEEDS_CONFIRMATION or REJECTED, never CONFIRMED.
 */
public record TripIntentResult(
        Status status,
        Long originRankId,
        String originRankName,
        Long destinationRankId,
        String destinationRankName,
        Long routeId,
        Integer partySize,
        List<RankCandidate> originAlternatives,
        List<RankCandidate> destinationAlternatives,
        DecisionMetadata metadata
) {
    public enum Status { CONFIRMED, NEEDS_CONFIRMATION, REJECTED }

    public record RankCandidate(Long rankId, String rankName, double matchScore) {}
}
