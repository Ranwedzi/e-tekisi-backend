package com.etekisi.etekisi.dto.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Driver sign-up payload. Has no {@code id}, {@code balance},
 * {@code tripsCompleted} or {@code active}: those are server-owned.
 */
public record RegisterDriverRequest(
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank
        @Pattern(regexp = "^[+]?[0-9 ()-]{8,20}$", message = "phoneNumber must be 8-20 digits/+/-/space/parentheses")
        String phoneNumber,
        // Normalised below to uppercase with spaces/dashes removed, e.g. "gp 123-abc" -> "GP123ABC".
        @NotBlank
        @Pattern(regexp = "^[A-Z0-9]{4,10}$", message = "taxiPlate must be 4-10 letters or digits")
        String taxiPlate,
        @NotBlank @Size(max = 50) String licenseNumber,
        @NotBlank @Size(min = 8, max = 200, message = "password must be at least 8 characters") String password
) {
    public RegisterDriverRequest {
        fullName = fullName == null ? null : fullName.trim();
        email = email == null ? null : email.trim().toLowerCase();
        phoneNumber = phoneNumber == null ? null : phoneNumber.trim();
        taxiPlate = taxiPlate == null ? null : taxiPlate.replaceAll("[\\s-]", "").toUpperCase();
        licenseNumber = licenseNumber == null ? null : licenseNumber.trim();
    }
}
