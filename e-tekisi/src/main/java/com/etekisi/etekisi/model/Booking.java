package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Entity
@Table(name = "bookings", schema = "commuter_schema")
@Data
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    private Long commuterId;

    private Long driverId;

    @NotBlank
    @Size(max = 200)
    private String pickupLocation;

    @NotBlank
    @Size(max = 200)
    private String destination;

    @Size(max = 50)
    private String scheduledTime;

    @PositiveOrZero
    private Double fare;

    @PositiveOrZero
    private Double tip;

    @Size(max = 32)
    private String status = "PENDING";
}
