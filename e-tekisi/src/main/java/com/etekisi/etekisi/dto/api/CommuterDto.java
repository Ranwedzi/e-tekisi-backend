package com.etekisi.etekisi.dto.api;

import com.etekisi.etekisi.model.User;

/**
 * Bounded API projection of {@link User} (a "commuter" in domain terms).
 *
 * <p>Drops the bcrypt password hash. Email is included because the
 * commuter-self-fetch endpoint legitimately needs it; do NOT add a
 * "list all commuters" endpoint that returns this DTO without further
 * filtering.
 */
public record CommuterDto(
        Long id,
        String fullName,
        String email,
        String phoneNumber,
        boolean emailVerified,
        boolean phoneVerified
) {
    public static CommuterDto from(User u) {
        if (u == null) return null;
        return new CommuterDto(
                u.getId(),
                u.getFullName(),
                u.getEmail(),
                u.getPhoneNumber(),
                u.getEmailVerifiedAt() != null,
                u.getPhoneVerifiedAt() != null
        );
    }
}
