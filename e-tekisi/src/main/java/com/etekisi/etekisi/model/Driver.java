package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/** API input uses RegisterDriverRequest; API output uses DriverDto. */
@Entity
@Table(name = "drivers", schema = "driver_schema")
@Data
public class Driver {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer tripsCompleted = 0;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 10)
    private String taxiPlate;

    @Column(nullable = false, length = 20)
    private String phoneNumber;

    @Column(nullable = false, length = 50)
    private String licenseNumber;

    @Column(nullable = false, length = 200, unique = true)
    private String email;

    /** BCrypt hash. */
    @Column(nullable = false, length = 200)
    private String password;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = false;
}
