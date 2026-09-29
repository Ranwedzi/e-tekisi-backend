package com.etekisi.etekisi.agent.dto;

public record CapacitySuggestion(
        Long routeId,
        int seatsTaken,
        int seatsRemaining,
        Suggestion suggestion,
        DecisionMetadata metadata
) {
    public enum Suggestion { DEPART_NOW, WAIT_FOR_FILL, INSUFFICIENT_DATA }
}
