package com.etekisi.etekisi.agent.dto;

import java.util.List;

/**
 * Authoritative output of the Dispatch & Matching engine.
 * Status is the only thing downstream code branches on; the engine
 * never silently assigns a driver — assignedDriverId is null unless
 * status == ASSIGNED.
 */
public record DispatchDecision(
        Long bookingId,
        Status status,
        Long assignedDriverId,
        List<Candidate> rankedCandidates,
        DecisionMetadata metadata
) {
    public enum Status { ASSIGNED, NO_MATCH, DEGRADED_NO_MATCH, REJECTED }

    public record Candidate(
            Long driverId,
            double score,
            double distanceMeters,
            int queuePosition,
            int recentDeclines,
            boolean locationFresh,
            List<ReasonCode> notes
    ) {}
}
