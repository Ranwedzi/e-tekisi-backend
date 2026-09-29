package com.etekisi.etekisi.agent.event;

import java.time.Instant;

/**
 * Marker interface for every Tekisi domain event consumed by the agent layer.
 *
 * Designed so migration to Kafka later is mechanical: each event already
 * carries its own correlationId and occurredAt, so it can be serialized
 * straight onto a topic without losing trace context.
 */
public interface TekisiEvent {
    String correlationId();
    Instant occurredAt();
    /** Stable channel name — also doubles as the future Kafka topic. */
    String channel();
}
