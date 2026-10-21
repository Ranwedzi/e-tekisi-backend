package com.etekisi.etekisi.push;

import com.etekisi.etekisi.model.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A browser or phone that asked for push notifications for one account. */
@Entity
@Table(name = "push_subscriptions", schema = "commuter_schema")
@Getter
@Setter
public class PushSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_role", nullable = false, length = 16)
    private Role ownerRole;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** The push service URL for this browser (unique; it identifies the subscription). */
    @Column(nullable = false, length = 1000, unique = true)
    private String endpoint;

    /** The browser's public key (base64url, 65-byte P-256 point). */
    @Column(nullable = false, length = 200)
    private String p256dh;

    /** The browser's auth secret (base64url, 16 bytes). */
    @Column(nullable = false, length = 100)
    private String auth;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(nullable = false)
    private int failures = 0;
}
