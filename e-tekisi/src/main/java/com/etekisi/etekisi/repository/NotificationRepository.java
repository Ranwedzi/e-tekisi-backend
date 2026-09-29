package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.DashboardNotification;
import com.etekisi.etekisi.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<DashboardNotification, Long> {

    List<DashboardNotification> findByUserIdAndRoleAndIsReadFalseOrderByCreatedAtDesc(Long userId, Role role);

    long countByUserIdAndRoleAndIsReadFalse(Long userId, Role role);
}
