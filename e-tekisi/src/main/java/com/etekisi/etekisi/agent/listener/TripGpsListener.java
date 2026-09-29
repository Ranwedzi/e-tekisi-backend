package com.etekisi.etekisi.agent.listener;

import com.etekisi.etekisi.agent.event.DriverLocationUpdatedEvent;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.agent.observability.CorrelationContext;
import com.etekisi.etekisi.agent.trip.TripGpsPing;
import com.etekisi.etekisi.agent.trip.TripGpsPingRepository;
import com.etekisi.etekisi.model.Booking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * When a driver location update arrives, attach it to any ACCEPTED booking
 * the driver currently has so SafetyAgent has authoritative trip history.
 *
 * Anti-hallucination posture:
 *   - only attaches the ping to bookings with status ACCEPTED (not GUESS-ed)
 *   - silently no-ops if there's no active booking for that driver
 */
@Component
public class TripGpsListener {

    private static final Logger log = LoggerFactory.getLogger(TripGpsListener.class);

    private final TrustedDataService trusted;
    private final TripGpsPingRepository tripGps;

    public TripGpsListener(TrustedDataService trusted, TripGpsPingRepository tripGps) {
        this.trusted = trusted;
        this.tripGps = tripGps;
    }

    @EventListener
    public void onDriverLocationUpdated(DriverLocationUpdatedEvent event) {
        try {
            CorrelationContext.set(event.correlationId());
            TrustedFact<List<Booking>> active = trusted.getActiveBookingsForDriver(event.driverId());
            if (!active.isPresent() || active.value().isEmpty()) return;
            for (Booking b : active.value()) {
                TripGpsPing ping = new TripGpsPing();
                ping.setBookingId(b.getId());
                ping.setLat(event.lat());
                ping.setLng(event.lng());
                ping.setRecordedAt(event.occurredAt());
                ping.setSource("DRIVER_APP");
                tripGps.save(ping);
            }
        } catch (RuntimeException e) {
            log.error("agent.trip.gps.attach.failed driverId={} error={}",
                    event.driverId(), e.getMessage());
        } finally {
            CorrelationContext.clear();
        }
    }
}
