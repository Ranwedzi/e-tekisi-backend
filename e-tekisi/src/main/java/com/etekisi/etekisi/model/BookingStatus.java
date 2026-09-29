package com.etekisi.etekisi.model;

/**
 * Booking lifecycle: PENDING -> ACCEPTED -> COMPLETED, or -> CANCELLED from
 * PENDING / ACCEPTED. Stored as strings, so the values must never be renamed.
 */
public enum BookingStatus {
    PENDING,
    ACCEPTED,
    COMPLETED,
    CANCELLED
}
