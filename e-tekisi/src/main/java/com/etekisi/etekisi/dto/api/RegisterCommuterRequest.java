package com.etekisi.etekisi.dto.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Commuter sign-up payload. Deliberately has no {@code id}: binding the JPA
 * entity directly let a caller send an existing id and overwrite that account.
 */
public record RegisterCommuterRequest(
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank
        @Pattern(regexp = "^[+]?[0-9 ()-]{8,20}$", message = "phoneNumber must be 8-20 digits/+/-/space/parentheses")
        String phoneNumber,
        @NotBlank @Size(min = 10, max = 200, message = "password must be at least 10 characters") String password,
        /** Must be true: the person ticked "I accept the terms of use and privacy policy". */
        Boolean acceptTerms,
        /** The version shown to them (GET /api/legal); refused if it is no longer current. */
        @Size(max = 40) String termsVersion
) {
    public RegisterCommuterRequest {
        fullName = fullName == null ? null : fullName.trim();
        email = email == null ? null : email.trim().toLowerCase();
        phoneNumber = phoneNumber == null ? null : phoneNumber.trim();
    }
}
