package com.etekisi.etekisi.model;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "messages", schema = "commuter_schema")
@Data
public class Message {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long bookingId;
    private Long senderId;
    private String content;
    private LocalDateTime timestamp = LocalDateTime.now();
}