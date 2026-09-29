package com.etekisi.etekisi.dto.api;

import com.etekisi.etekisi.model.Driver;

/**
 * Bounded API projection of {@link Driver}.
 *
 * <p>Critical security note: the source entity carries the bcrypt-hashed
 * password and the email used as a login identifier. This projection drops
 * the password hash entirely and exposes the email only on owner-scoped
 * endpoints (the frontend should not surface other drivers' emails).
 */
public record DriverDto(
        Long id,
        String fullName,
        String email,
        String phoneNumber,
        String taxiPlate,
        String licenseNumber,
        Double balance,
        Integer tripsCompleted,
        boolean isActive
) {
    public static DriverDto from(Driver d) {
        if (d == null) return null;
        return new DriverDto(
                d.getId(),
                d.getFullName(),
                d.getEmail(),
                d.getPhoneNumber(),
                d.getTaxiPlate(),
                d.getLicenseNumber(),
                d.getBalance(),
                d.getTripsCompleted(),
                d.isActive()
        );
    }
}
