package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Entity
@Table(name = "payout_requests", schema = "driver_schema")
@Data
public class PayoutRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long driverId;
    private Double amount;
    private String method; // BANK_TRANSFER | FNB_EWALLET | CAPITEC_CASH_SEND

    private String bankName;
    private String accountNumber;
    private String accountHolder;
    private String phoneNumber;

    private String status = "PENDING";
    private Instant createdAt = Instant.now();
}
