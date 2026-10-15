package com.etekisi.etekisi.model;

/**
 * Booking lifecycle. Stored as strings, so the values must never be renamed.
 *
 * <pre>
 *   PENDING --driver offers fare--> OFFERED --commuter accepts (wallet charged)--> ACCEPTED --complete--> COMPLETED
 *      ^                               |
 *      +-- commuter declines / driver withdraws / offer expires
 *
 *   PENDING, OFFERED or ACCEPTED --cancel--> CANCELLED   (ACCEPTED -> CANCELLED refunds the commuter)
 * </pre>
 */
public enum BookingStatus {
    PENDING,
    OFFERED,
    ACCEPTED,
    COMPLETED,
    CANCELLED
}
