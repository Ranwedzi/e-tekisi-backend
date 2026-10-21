package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "saved_ranks", schema = "commuter_schema")
@Getter
@Setter
public class SavedRank {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long commuterId;
    private String name;
    private String address;
    private Instant createdAt = Instant.now();
}
