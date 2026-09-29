package com.etekisi.etekisi.dto.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** The commuter id is taken from the token, never from this body. */
public record CreateBookingRequest(
        @NotBlank @Size(max = 200) String pickupLocation,
        @NotBlank @Size(max = 200) String destination,
        @Size(max = 50) String scheduledTime,
        @PositiveOrZero @DecimalMax("500.00") @Digits(integer = 10, fraction = 2) BigDecimal tip
) {}
