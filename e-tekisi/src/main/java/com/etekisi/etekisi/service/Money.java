package com.etekisi.etekisi.service;

import com.etekisi.etekisi.web.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rand amounts: two decimal places, validated at the edge of every money operation. */
public final class Money {

    private Money() {}

    public static BigDecimal of(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(double amount) {
        return of(BigDecimal.valueOf(amount));
    }

    /**
     * Rejects null, zero/negative, more than 2 decimal places, or more than
     * {@code max}. Returns the amount scaled to 2 decimals.
     */
    public static BigDecimal requireAmount(BigDecimal amount, BigDecimal min, BigDecimal max, String field) {
        if (amount == null) {
            throw ApiException.badRequest("INVALID_AMOUNT", field + " is required");
        }
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", field + " must be greater than 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest("INVALID_AMOUNT", field + " can have at most 2 decimal places");
        }
        if (min != null && amount.compareTo(min) < 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", field + " must be at least R" + of(min));
        }
        if (max != null && amount.compareTo(max) > 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", field + " must be at most R" + of(max));
        }
        return of(amount);
    }
}
