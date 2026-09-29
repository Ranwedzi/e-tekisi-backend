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
 * <p>The contact fields ({@code driverName}, {@code driverPhone},
 * {@code taxiPlate}, {@code commuterName}, {@code commuterPhone}) are only
 * filled in for the booking's own participants once a driver has accepted;
 * otherwise they are null.
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
        String driverName,
        String driverPhone,
        String taxiPlate,
        String commuterName,
        String commuterPhone
) {
    public static BookingDto from(Booking b) {
        return from(b, null, null);
    }

    public static BookingDto from(Booking b, Driver driver, User commuter) {
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
                driver != null ? driver.getFullName() : null,
                driver != null ? driver.getPhoneNumber() : null,
                driver != null ? driver.getTaxiPlate() : null,
                commuter != null ? commuter.getFullName() : null,
                commuter != null ? commuter.getPhoneNumber() : null
        );
    }
}
