package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.dto.api.ApiRecords.CountResponse;
import com.etekisi.etekisi.dto.api.ApiRecords.NotificationDto;
import com.etekisi.etekisi.model.DashboardNotification;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.NotificationRepository;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.web.ApiException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The caller's own notifications. Who the caller is comes from the JWT. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notifications;
    private final CurrentUser currentUser;

    public NotificationController(NotificationRepository notifications, CurrentUser currentUser) {
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    /** Unread notifications, newest first. */
    @GetMapping
    public List<NotificationDto> getNotifications() {
        return notifications
                .findByUserIdAndRoleAndIsReadFalseOrderByCreatedAtDesc(currentUser.id(), currentUser.role())
                .stream().map(NotificationDto::from).toList();
    }

    @GetMapping("/unread-count")
    public CountResponse unreadCount() {
        return new CountResponse(notifications.countByUserIdAndRoleAndIsReadFalse(currentUser.id(), currentUser.role()));
    }

    @PutMapping("/read/{id}")
    @Transactional
    public void markAsRead(@PathVariable Long id) {
        DashboardNotification n = ownNotification(id);
        n.setRead(true);
    }

    @PutMapping("/read-all")
    @Transactional
    public void markAllAsRead() {
        List<DashboardNotification> unread = notifications
                .findByUserIdAndRoleAndIsReadFalseOrderByCreatedAtDesc(currentUser.id(), currentUser.role());
        unread.forEach(n -> n.setRead(true));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void deleteNotification(@PathVariable Long id) {
        notifications.delete(ownNotification(id));
    }

    private DashboardNotification ownNotification(Long id) {
        DashboardNotification n = notifications.findById(id).orElseThrow(() -> ApiException.notFound("Notification"));
        Role role = currentUser.role();
        if (!n.getUserId().equals(currentUser.id()) || n.getRole() != role) {
            throw new AccessDeniedException("Not your notification");
        }
        return n;
    }
}
