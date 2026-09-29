package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "driver_locations", schema = "driver_schema")
@Data
public class DriverLocation {
    @Id
    private Long driverId;
    private Double lat;
    private Double lng;
    private LocalDateTime lastUpdated;
}