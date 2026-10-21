package com.etekisi.etekisi.session;

import com.etekisi.etekisi.model.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One refresh token of a browser session. A sign-in starts a family; every
 * refresh marks the token used and adds the next one to the same family.
 */
@Entity
@Table(name = "auth_sessions", schema = "commuter_schema")
@Getter
@Setter
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_role", nullable = false, length = 16)
    private Role ownerRole;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** SHA-256 of the token (hex); the token itself is only ever in the cookie. */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** When the person signed in: the session can't outlive the account's last password change. */
    @Column(name = "family_created_at", nullable = false)
    private Instant familyCreatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "user_agent", length = 200)
    private String userAgent;
}
