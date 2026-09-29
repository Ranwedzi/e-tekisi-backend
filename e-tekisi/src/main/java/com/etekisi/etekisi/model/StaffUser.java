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
}
