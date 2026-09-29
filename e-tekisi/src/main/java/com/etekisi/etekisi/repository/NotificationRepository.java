package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.DashboardNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NotificationRepository extends JpaRepository<DashboardNotification, Long> {

    // Finds unread notifications for a specific user
    List<DashboardNotification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId);
    List<DashboardNotification> findByUserIdAndRoleAndIsReadFalseOrderByCreatedAtDesc(Long userId, String role);
}
