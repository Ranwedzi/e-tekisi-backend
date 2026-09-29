package com.etekisi.etekisi.model;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "notifications", schema = "commuter_schema")
@Data
public class DashboardNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private String role = "COMMUTER";
    private String message;
    private boolean isRead = false;
    private LocalDateTime createdAt = LocalDateTime.now();
}
