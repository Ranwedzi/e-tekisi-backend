package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "taxi_ranks", schema = "driver_schema")
@Getter
@Setter
public class TaxiRank {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String location; // GPS Coordinates or Area
    private String status = "ACTIVE";
}