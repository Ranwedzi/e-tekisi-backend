package com.etekisi.etekisi.agent.event;

import com.etekisi.etekisi.agent.observability.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Single seam for emitting agent-layer events.
 *
 * - Stamps every event with the current correlation id (or generates one).
 * - Logs structured channel + correlation id for traceability.
 * - Today routes to {@link ApplicationEventPublisher}; swapping to Kafka
 *   means changing this one class.
 */
@Component
public class AgentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AgentEventPublisher.class);
    private final ApplicationEventPublisher publisher;

    public AgentEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(TekisiEvent event) {
        if (event == null) return;
        log.info("agent.event.publish channel={} correlationId={} occurredAt={}",
                event.channel(), event.correlationId(), event.occurredAt());
        publisher.publishEvent(event);
    }

    public BookingCreatedEvent bookingCreated(Long bookingId, Long commuterId,
                                              String pickup, String destination,
                                              Double lat, Double lng) {
        return new BookingCreatedEvent(bookingId, commuterId, pickup, destination,
                lat, lng, Instant.now(), CorrelationContext.getOrCreate());
    }

    public DriverLocationUpdatedEvent driverLocationUpdated(Long driverId, double lat, double lng) {
        return new DriverLocationUpdatedEvent(driverId, lat, lng, Instant.now(), CorrelationContext.getOrCreate());
    }

    public TripStartedEvent tripStarted(Long bookingId, Long driverId, Long commuterId, Double fare) {
        return new TripStartedEvent(bookingId, driverId, commuterId, fare, Instant.now(), CorrelationContext.getOrCreate());
    }

    public TripCompletedEvent tripCompleted(Long bookingId, Long driverId, Long commuterId, Double fare, Double tip) {
        return new TripCompletedEvent(bookingId, driverId, commuterId, fare, tip, Instant.now(), CorrelationContext.getOrCreate());
    }
}
