package com.etekisi.etekisi.dto.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** The commuter id is taken from the token, never from this body. */
public record CreateBookingRequest(
        @NotBlank @Size(max = 200) String pickupLocation,
        @NotBlank @Size(max = 200) String destination,
        @Size(max = 50) String scheduledTime,
        @PositiveOrZero @DecimalMax("500.00") @Digits(integer = 10, fraction = 2) BigDecimal tip,
        // Optional coordinates (map pin, GPS or geocoded); latitude and longitude come as a pair.
        @DecimalMin("-90") @DecimalMax("90") Double pickupLat,
        @DecimalMin("-180") @DecimalMax("180") Double pickupLng,
        @DecimalMin("-90") @DecimalMax("90") Double destinationLat,
        @DecimalMin("-180") @DecimalMax("180") Double destinationLng,
        // When to leave, ISO-8601 with a zone ("2026-10-22T05:30:00Z"). Null = now. The older
        // scheduledTime (local South African time, "2026-10-22T07:30") is still accepted.
        Instant scheduledAt
) {
    /** The old four-field form (no coordinates). */
    public CreateBookingRequest(String pickupLocation, String destination, String scheduledTime, BigDecimal tip) {
        this(pickupLocation, destination, scheduledTime, tip, null, null, null, null, null);
    }
}
