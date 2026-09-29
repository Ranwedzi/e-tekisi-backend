package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "wallets", schema = "commuter_schema")
@Data
public class Wallet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String role; // "COMMUTER" or "DRIVER"
    private Double balance = 0.0;
    private Integer totalTrips = 0;
}