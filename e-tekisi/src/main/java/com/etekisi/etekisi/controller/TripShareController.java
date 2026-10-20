package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.security.LoginThrottle;
import com.etekisi.etekisi.service.TripShareService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * "Share my trip". The commuter creates or stops links under
 * {@code /api/bookings/{id}/share}; whoever has a link reads
 * {@code GET /api/share/{token}} without signing in.
 */
@RestController
public class TripShareController {

    private final TripShareService shares;
    private final CurrentUser currentUser;
    private final LoginThrottle throttle;

    public TripShareController(TripShareService shares, CurrentUser currentUser, LoginThrottle throttle) {
        this.shares = shares;
        this.currentUser = currentUser;
        this.throttle = throttle;
    }

    @PostMapping("/api/bookings/{id}/share")
    public TripShareService.Created share(@PathVariable Long id) {
        return shares.create(id, currentUser.requireRole(Role.COMMUTER));
    }

    @DeleteMapping("/api/bookings/{id}/share")
    public Map<String, Object> stop(@PathVariable Long id) {
        return Map.of("stopped", shares.revoke(id, currentUser.requireRole(Role.COMMUTER)));
    }

    @GetMapping("/api/share/{token}")
    public ResponseEntity<Map<String, Object>> view(@PathVariable String token, HttpServletRequest http) {
        // The viewer polls; the per-IP limit stops anyone trying link after link.
        throttle.checkIp("share:" + http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shares.view(token));
    }
}
