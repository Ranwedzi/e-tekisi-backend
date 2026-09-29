package com.etekisi.etekisi.agent.event;

import java.time.Instant;

public record BookingCreatedEvent(
        Long bookingId,
        Long commuterId,
        String pickupLocation,
        String destination,
        Double commuterLat,
        Double commuterLng,
        Instant occurredAt,
        String correlationId
) implements TekisiEvent {
    @Override public String channel() { return "booking.created"; }
}
