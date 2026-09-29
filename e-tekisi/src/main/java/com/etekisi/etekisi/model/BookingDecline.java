package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "booking_declines", schema = "driver_schema")
@Data
public class BookingDecline {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long bookingId;
    private Long driverId;
    private LocalDateTime createdAt = LocalDateTime.now();
}
