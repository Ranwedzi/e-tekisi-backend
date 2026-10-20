package com.etekisi.etekisi.dto.api;

import com.etekisi.etekisi.model.Driver;

import java.math.BigDecimal;

/**
 * Bounded API projection of {@link Driver}.
 *
 * <p>Critical security note: the source entity carries the bcrypt-hashed
 * password. This projection drops it. It is only returned on owner-scoped
 * endpoints, so the email and balance are safe to include.
 *
 * <p>The online flag is named {@code active} (not {@code isActive}) because
 * that is the JSON property the frontend reads.
 */
public record DriverDto(
        Long id,
        String fullName,
        String email,
        String phoneNumber,
        String taxiPlate,
        String licenseNumber,
        BigDecimal balance,
        Integer tripsCompleted,
        boolean active,
        String approvalStatus,
        String approvalNote
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
                d.isActive(),
                d.getApprovalStatus() != null ? d.getApprovalStatus().name() : null,
                d.getApprovalNote()
        );
    }
}
