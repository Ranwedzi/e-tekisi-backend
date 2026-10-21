package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "routes", schema = "commuter_schema")
@Getter
@Setter
public class Route {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String startLocation; // e.g., Soweto
    private String destination;   // e.g., Sandton
    private String suggestedRank; // e.g., Baragwanath Taxi Rank
    private String price;         // e.g., R35.00
    private String estimatedTime; // e.g., 45 mins
}