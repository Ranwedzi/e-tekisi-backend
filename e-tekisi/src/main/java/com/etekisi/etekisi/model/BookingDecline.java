package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "booking_declines", schema = "driver_schema")
@Getter
@Setter
public class BookingDecline {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long bookingId;
    private Long driverId;
    private LocalDateTime createdAt = LocalDateTime.now();
}
