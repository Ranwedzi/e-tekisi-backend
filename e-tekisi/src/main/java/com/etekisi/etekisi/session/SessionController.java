package com.etekisi.etekisi.session;

import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.StaffUserRepository;
import com.etekisi.etekisi.repository.UserRepository;
import com.etekisi.etekisi.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Browser sessions (cookies, see {@link SessionService}):
 * <ul>
 *   <li>{@code POST /api/auth/session/refresh}: new cookies from the refresh
 *       cookie; 401 SESSION_ENDED when it's gone.</li>
 *   <li>{@code POST /api/auth/session/logout}: ends this browser's session.</li>
 *   <li>{@code GET /api/me/sessions}: where the account is signed in.</li>
 *   <li>{@code POST /api/me/sessions/revoke-all}: signs out everywhere, this
 *       browser included.</li>
 * </ul>
 */
@RestController
public class SessionController {

    private final SessionService sessions;
    private final SessionCookies cookies;
    private final CurrentUser currentUser;
    private final UserRepository users;
    private final DriverRepository drivers;
    private final StaffUserRepository staff;

    public SessionController(SessionService sessions, SessionCookies cookies, CurrentUser currentUser,
                             UserRepository users, DriverRepository drivers, StaffUserRepository staff) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.currentUser = currentUser;
        this.users = users;
        this.drivers = drivers;
        this.staff = staff;
    }

    @PostMapping("/api/auth/session/refresh")
    public Map<String, Object> refresh(HttpServletRequest req, HttpServletResponse res) {
        return sessions.refresh(req, res);
    }

    @PostMapping("/api/auth/session/logout")
    public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
        sessions.signOut(req, res);
        return Map.of("status", "SIGNED_OUT");
    }

    @GetMapping("/api/me/sessions")
    public List<SessionService.Device> list() {
        return sessions.devices(currentUser.role(), currentUser.id());
    }

    @PostMapping("/api/me/sessions/revoke-all")
    @Transactional
    public Map<String, Object> revokeAll(HttpServletResponse res) {
        Role role = currentUser.role();
        Long id = currentUser.id();
        int ended = sessions.revokeAll(role, id);
        // Access tokens issued before now stop working at once (AccountStateTokenValidator).
        Instant now = Instant.now();
        switch (role) {
            case COMMUTER -> users.findById(id).ifPresent(u -> u.setCredentialsChangedAt(now));
            case DRIVER -> drivers.findById(id).ifPresent(d -> d.setCredentialsChangedAt(now));
            case OPERATOR, ADMIN -> staff.findById(id).ifPresent(s -> s.setCredentialsChangedAt(now));
        }
        cookies.clear(res);
        return Map.of("status", "SIGNED_OUT_EVERYWHERE", "sessionsEnded", ended);
    }
}
