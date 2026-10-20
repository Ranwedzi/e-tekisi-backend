package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.DashboardNotification;
import com.etekisi.etekisi.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

import java.util.List;

public interface NotificationRepository extends JpaRepository<DashboardNotification, Long> {

    List<DashboardNotification> findByUserIdAndRoleAndIsReadFalseOrderByCreatedAtDesc(Long userId, Role role);

    long countByUserIdAndRoleAndIsReadFalse(Long userId, Role role);

    List<DashboardNotification> findByUserIdAndRoleOrderByCreatedAtDesc(Long userId, Role role);

    long deleteByUserIdAndRole(Long userId, Role role);

    /** Retention: notifications older than the cutoff. */
    @Modifying
    @Query("delete from DashboardNotification n where n.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
