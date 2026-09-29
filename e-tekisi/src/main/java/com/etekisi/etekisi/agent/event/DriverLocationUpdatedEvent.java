package com.etekisi.etekisi.agent.event;

import java.time.Instant;

public record DriverLocationUpdatedEvent(
        Long driverId,
        double lat,
        double lng,
        Instant occurredAt,
        String correlationId
) implements TekisiEvent {
    @Override public String channel() { return "driver.location.updated"; }
}
