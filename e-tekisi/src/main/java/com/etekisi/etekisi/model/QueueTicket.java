package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@EntityListeners(com.etekisi.etekisi.realtime.LiveEntityListeners.Queue.class)
@Table(name = "queue_tickets", schema = "driver_schema")
@Getter
@Setter
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