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
        String commuterPhone
) {
    public static BookingDto from(Booking b) {
        return from(b, null, null, false);
    }

    public static BookingDto from(Booking b, Driver driver, User commuter, boolean includePhones) {
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
                b.getOfferExpiresAt() != null ? b.getOfferExpiresAt().toString() : null,
                driver != null ? driver.getFullName() : null,
                driver != null && includePhones ? driver.getPhoneNumber() : null,
                driver != null ? driver.getTaxiPlate() : null,
                commuter != null ? commuter.getFullName() : null,
                commuter != null && includePhones ? commuter.getPhoneNumber() : null
        );
    }
}
