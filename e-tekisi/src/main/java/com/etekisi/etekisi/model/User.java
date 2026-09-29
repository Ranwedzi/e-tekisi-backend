package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Entity
@Table(name = "users", schema = "commuter_schema")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(min = 2, max = 120)
    private String fullName;

    @NotBlank
    @Email
    @Size(max = 200)
    private String email;

    @NotBlank
    // SA mobile numbers are typically 10 digits; permit + and country-code variations.
    @Pattern(regexp = "^[+]?[0-9 ()-]{8,20}$", message = "phoneNumber must be 8-20 digits/+/-/space/parentheses")
    private String phoneNumber;

    @NotBlank
    @Size(min = 6, max = 200)
    private String password;
}
