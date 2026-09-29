package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.DashboardNotification;
import com.etekisi.etekisi.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private NotificationRepository notificationRepository;

    /**
     * GET: Fetch real-time unread notifications for a specific user.
     * This uses the Repository method you provided to pull data directly from the DB.
     */
    @GetMapping("/{role}/{userId}")
    public List<DashboardNotification> getNotifications(@PathVariable String role, @PathVariable Long userId) {
        return notificationRepository.findByUserIdAndRoleAndIsReadFalseOrderByCreatedAtDesc(userId, role);
    }

    /**
     * PUT: Mark a specific notification as read.
     * When the user clicks an individual notification, this updates the DB.
     */
    @PutMapping("/read/{id}")
    public void markAsRead(@PathVariable Long id) {
        notificationRepository.findById(id).ifPresent(notification -> {
            notification.setRead(true);
            notificationRepository.save(notification);
        });
    }

    /**
     * PUT: Mark all notifications as read for a specific user.
     * This is called when the user clicks "Clear" or "Mark all as read" in the UI.
     */
    @PutMapping("/read-all/{role}/{userId}")
    public void markAllAsRead(@PathVariable String role, @PathVariable Long userId) {
        List<DashboardNotification> unread = notificationRepository
                .findByUserIdAndRoleAndIsReadFalseOrderByCreatedAtDesc(userId, role);

        unread.forEach(n -> n.setRead(true));
        notificationRepository.saveAll(unread);
    }

    /**
     * DELETE: Permanently remove a notification from the database.
     */
    @DeleteMapping("/{id}")
    public void deleteNotification(@PathVariable Long id) {
        notificationRepository.deleteById(id);
    }

    /**
     * DELETE: Remove all notifications.
     */
    @DeleteMapping("/clear-all")
    public void clearAll() {
        notificationRepository.deleteAll();
    }
}
