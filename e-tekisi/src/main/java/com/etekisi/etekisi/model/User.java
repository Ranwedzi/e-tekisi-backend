package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;

/** A commuter account. API input uses RegisterCommuterRequest; API output uses CommuterDto. */
@Entity
@Table(name = "users", schema = "commuter_schema")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 200, unique = true)
    private String email;

    @Column(nullable = false, length = 20)
    private String phoneNumber;

    /** BCrypt hash. */
    @Column(nullable = false, length = 200)
    private String password;
}
