package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A one-time email link or SMS code. Only its SHA-256 hash is stored. */
@Entity
@Table(name = "account_tokens", schema = "commuter_schema")
@Getter
@Setter
public class AccountToken {

    public enum Purpose { VERIFY_EMAIL, VERIFY_PHONE, RESET_PASSWORD }
    public enum Channel { EMAIL, SMS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role ownerRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Purpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Channel channel;

    @Column(nullable = false, length = 64)
    private String secretHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
