package com.etekisi.etekisi.agent.event;

import java.time.Instant;

public record TripCompletedEvent(
        Long bookingId,
        Long driverId,
        Long commuterId,
        Double fare,
        Double tip,
        Instant occurredAt,
        String correlationId
) implements TekisiEvent {
    @Override public String channel() { return "trip.completed"; }
}
