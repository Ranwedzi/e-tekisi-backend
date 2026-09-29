package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "queue_tickets", schema = "driver_schema")
@Data
public class QueueTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long driverId;
    private String driverName;
    private String taxiRank;

    // Statuses: WAITING, SERVING, COMPLETED
    private String status = "WAITING";

    private int skipCount = 0;
    private LocalDateTime joinedAt = LocalDateTime.now();
    private LocalDateTime servedAt;
}