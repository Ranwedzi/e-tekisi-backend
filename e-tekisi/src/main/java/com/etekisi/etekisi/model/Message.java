package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@EntityListeners(com.etekisi.etekisi.realtime.LiveEntityListeners.Messages.class)
@Table(name = "messages", schema = "commuter_schema")
@Data
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long bookingId;

    @Column(nullable = false)
    private Long senderId;

    /** Commuter and driver ids come from separate sequences, so the id alone is ambiguous. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Role senderRole;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();
}
