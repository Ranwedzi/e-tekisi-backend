package com.etekisi.etekisi.agent.idempotency;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Persistent idempotency receipt — see V3__idempotency_keys.sql and V8.
 *
 * <p>Claimed ({@code IN_PROGRESS}) before the write runs and completed with
 * the response afterwards. Every later request bearing the same
 * {@code Idempotency-Key} for the same user + route gets the stored
 * response. Uniqueness on (key, route) is the contract.
 */
@Entity
@Table(
    name = "idempotency_keys",
    schema = "agent_schema",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_idempotency_key_route",
        columnNames = {"idempotency_key", "route"}
    ),
    indexes = @Index(name = "idx_idempotency_keys_expires", columnList = "expires_at")
)
@Data
public class IdempotencyKey {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 128)
    private String route;

    @Column(nullable = false, length = 16)
    private String state = COMPLETED;

    @Column(name = "response_hash", length = 128)
    private String responseHash;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "status_code", nullable = false)
    private int statusCode = 200;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
