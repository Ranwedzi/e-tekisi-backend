package com.etekisi.etekisi.dto.api;

import com.etekisi.etekisi.model.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password,
        @NotNull Role role
) {}
