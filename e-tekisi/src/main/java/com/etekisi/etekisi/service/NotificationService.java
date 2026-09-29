package com.etekisi.etekisi.service;

import com.etekisi.etekisi.model.DashboardNotification;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository repo;

    public NotificationService(NotificationRepository repo) {
        this.repo = repo;
    }

    /** Joins the caller's transaction: the notification is only kept if the change it describes is. */
    @Transactional
    public void send(Long userId, Role role, String message) {
        if (userId == null) return;
        repo.save(build(userId, role, message));
    }

    /**
     * Commits in its own transaction, so it survives when the caller then
     * throws and rolls back (e.g. telling a commuter their wallet was too low
     * for a fare offer that is being rejected).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendIndependently(Long userId, Role role, String message) {
        if (userId == null) return;
        repo.save(build(userId, role, message));
    }

    private static DashboardNotification build(Long userId, Role role, String message) {
        DashboardNotification n = new DashboardNotification();
        n.setUserId(userId);
        n.setRole(role);
        n.setMessage(message.length() > 500 ? message.substring(0, 500) : message);
        return n;
    }
}
