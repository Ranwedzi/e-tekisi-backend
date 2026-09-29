package com.etekisi.etekisi.agent.event;

import java.time.Instant;

public record TripStartedEvent(
        Long bookingId,
        Long driverId,
        Long commuterId,
        Double fare,
        Instant occurredAt,
        String correlationId
) implements TekisiEvent {
    @Override public String channel() { return "trip.started"; }
}
