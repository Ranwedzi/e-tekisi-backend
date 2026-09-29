package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payout_requests", schema = "driver_schema")
@Data
public class PayoutRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long driverId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 32)
    private String method; // BANK_TRANSFER | FNB_EWALLET | CAPITEC_CASH_SEND

    private String bankName;
    private String accountNumber;
    private String accountHolder;
    private String phoneNumber;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
