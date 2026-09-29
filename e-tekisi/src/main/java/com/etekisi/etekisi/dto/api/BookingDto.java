package com.etekisi.etekisi.dto.api;

import com.etekisi.etekisi.model.Booking;

import java.util.Collection;
import java.util.List;

/**
 * Bounded API projection of {@link Booking}.
 *
 * <p>Exists to stop JPA entities from leaking through controller responses.
 * The entity carries Hibernate proxy metadata, lazy-loading hooks, and may
 * later gain audit columns we do not want to ship to the frontend by accident
 * — every field here is opted in deliberately.
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
        Double fare,
        Double tip,
        String status
) {
    public static BookingDto from(Booking b) {
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
                b.getStatus()
        );
    }

    public static List<BookingDto> from(Collection<Booking> bs) {
        if (bs == null) return List.of();
        return bs.stream().map(BookingDto::from).toList();
    }
}
