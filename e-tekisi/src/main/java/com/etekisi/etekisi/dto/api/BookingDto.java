package com.etekisi.etekisi.dto.api;

import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.User;

import java.math.BigDecimal;

/**
 * Bounded API projection of {@link Booking}.
 *
 * <p>Exists to stop JPA entities from leaking through controller responses
 * — every field here is opted in deliberately.
 *
 * <p>Participant details are shared step by step, and only with the booking's
 * own participants:
 * <ul>
 *   <li>OFFERED: names and the taxi plate, so the commuter can decide.</li>
 *   <li>ACCEPTED / COMPLETED: phone numbers too.</li>
 * </ul>
 * Otherwise these fields are null.
 *
 * <p>Frontend consumers should treat this as the contract.
 */
public record BookingDto(
        Long id,
        Long commuterId,
        Long driverId,
        String pickupLocation,
        String destination,
        String scheduledTime,
        BigDecimal fare,
        BigDecimal tip,
        String status,
        String offerExpiresAt,
        String driverName,
        String driverPhone,
        String taxiPlate,
        String commuterName,
        String commuterPhone,
        Double pickupLat,
        Double pickupLng,
        Double destinationLat,
        Double destinationLng,
        String arrivedAt,
        String startedAt,
        String completedAt,
        /** The driver's average stars (offers and trips), so a commuter can choose. */
        Double driverRating,
        Integer driverRatingCount,
        /** The commuter's average stars, shown to drivers (on open requests too, without the name). */
        Double commuterRating,
        Integer commuterRatingCount,
        /** Whether each side has rated this (completed) trip yet. */
        boolean commuterRated,
        boolean driverRated
) {
    public static BookingDto from(Booking b) {
        return from(b, null, null, false, false, false);
    }

    public static BookingDto from(Booking b, Driver driver, User commuter, boolean includePhones) {
        return from(b, driver, commuter, includePhones, false, false);
    }

    /** An open request as drivers see it: the commuter's rating, but not who they are. */
    public static BookingDto forOpenRequest(Booking b, User commuter) {
        BookingDto d = from(b);
        if (commuter == null) return d;
        return new BookingDto(d.id(), d.commuterId(), d.driverId(), d.pickupLocation(), d.destination(), d.scheduledTime(),
                d.fare(), d.tip(), d.status(), d.offerExpiresAt(), null, null, null, null, null,
                d.pickupLat(), d.pickupLng(), d.destinationLat(), d.destinationLng(), d.arrivedAt(), d.startedAt(),
                d.completedAt(), null, null, commuter.averageRating(), commuter.getRatingCount(), false, false);
    }

    public static BookingDto from(Booking b, Driver driver, User commuter, boolean includePhones,
                                  boolean commuterRated, boolean driverRated) {
        if (b == null) return null;
        return new BookingDto(
                b.getId(),
                b.getCommuterId(),
                b.getDriverId(),
                b.getPickupLocation(),
                b.getDestination(),
                b.getScheduledTime(),
                b.getFare(),
                b.getTip(),
                b.getStatus() != null ? b.getStatus().name() : null,
                str(b.getOfferExpiresAt()),
                driver != null ? driver.getFullName() : null,
                driver != null && includePhones ? driver.getPhoneNumber() : null,
                driver != null ? driver.getTaxiPlate() : null,
                commuter != null ? commuter.getFullName() : null,
                commuter != null && includePhones ? commuter.getPhoneNumber() : null,
                b.getPickupLat(), b.getPickupLng(), b.getDestinationLat(), b.getDestinationLng(),
                str(b.getArrivedAt()), str(b.getStartedAt()), str(b.getCompletedAt()),
                driver != null ? driver.averageRating() : null,
                driver != null ? driver.getRatingCount() : null,
                commuter != null ? commuter.averageRating() : null,
                commuter != null ? commuter.getRatingCount() : null,
                commuterRated, driverRated
        );
    }

    private static String str(java.time.Instant i) {
        return i != null ? i.toString() : null;
    }
}
