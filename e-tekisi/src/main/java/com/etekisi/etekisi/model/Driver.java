package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Entity
@Table(name = "drivers", schema = "driver_schema")
@Data
public class Driver {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Double balance = 0.0;
    private Integer tripsCompleted = 0;

    @NotBlank
    @Size(min = 2, max = 120)
    private String fullName;

    @NotBlank
    // SA taxi plates: 6-10 alphanumeric chars. Letters allowed (e.g. ABC123GP).
    @Pattern(regexp = "^[A-Z0-9]{4,10}$", message = "taxiPlate must be 4-10 uppercase alphanumeric chars")
    private String taxiPlate;

    @NotBlank
    @Pattern(regexp = "^[+]?[0-9 ()-]{8,20}$", message = "phoneNumber must be 8-20 digits/+/-/space/parentheses")
    private String phoneNumber;

    @NotBlank
    @Size(max = 50)
    private String licenseNumber;

    @NotBlank
    @Email
    @Size(max = 200)
    private String email;

    @NotBlank
    @Size(min = 6, max = 200)
    private String password;

    private boolean isActive = false;
}
