package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;

/** Operator / admin account. Seeded at startup by StaffBootstrap; there is no public sign-up. */
@Entity
@Table(name = "staff_users", schema = "agent_schema")
@Data
public class StaffUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 200, unique = true)
    private String email;

    /** BCrypt hash. */
    @Column(nullable = false, length = 200)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role = Role.OPERATOR;

    /** TOTP secret, encrypted with DATA_ENCRYPTION_KEY. Set during setup; in use once mfaEnabledAt is set. */
    @Column(name = "mfa_secret", length = 200)
    private String mfaSecret;

    @Column(name = "mfa_enabled_at")
    private java.time.Instant mfaEnabledAt;

    /** The last TOTP step accepted, so a code can't be used twice. */
    @Column(name = "mfa_last_step")
    private Long mfaLastStep;

    /** Comma-separated SHA-256 hashes of the unused recovery codes. */
    @Column(name = "mfa_recovery_hashes", length = 1000)
    private String mfaRecoveryHashes;

    /** Tokens issued before this are rejected. */
    @Column(name = "credentials_changed_at")
    private java.time.Instant credentialsChangedAt;

    public boolean isMfaEnabled() {
        return mfaEnabledAt != null;
    }
}
