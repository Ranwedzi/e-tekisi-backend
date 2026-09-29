package com.etekisi.etekisi.agent.event;

import com.etekisi.etekisi.agent.dto.SafetyAlert;

import java.time.Instant;

public record SafetyIncidentDetectedEvent(
        SafetyAlert alert,
        Instant occurredAt,
        String correlationId
) implements TekisiEvent {
    @Override public String channel() { return "safety.incident.detected"; }
}
