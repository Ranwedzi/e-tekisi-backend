package com.etekisi.etekisi.agent.dto;

public record EtaEstimate(
        Long bookingId,
        Long driverId,
        Integer meanSeconds,   // null if UNKNOWN
        Integer p90Seconds,    // null if UNKNOWN
        Source source,
        Status status,
        DecisionMetadata metadata
) {
    public enum Source { MODEL, HEURISTIC, ROUTING_API, NONE }
    public enum Status { OK, OUT_OF_RANGE, UNKNOWN }
}
