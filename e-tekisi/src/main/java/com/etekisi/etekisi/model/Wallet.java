package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Commuter wallet. Driver earnings live on {@link Driver#getBalance()}. */
@Entity
@Table(name = "wallets", schema = "commuter_schema",
       uniqueConstraints = @UniqueConstraint(name = "uq_wallets_user_role", columnNames = {"user_id", "role"}))
@Getter
@Setter
public class Wallet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role = Role.COMMUTER;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer totalTrips = 0;
}
