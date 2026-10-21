package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.session.SessionService;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.StaffUser;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.security.LoginThrottle;
import com.etekisi.etekisi.service.StaffMfaService;
import com.etekisi.etekisi.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Two-step sign-in for staff.
 *
 * <p>Public, with the {@code mfaToken} challenge from {@code /api/auth/login}:
 * {@code /api/auth/mfa/verify} (code, finishes the sign-in),
 * {@code /api/auth/mfa/setup} and {@code /api/auth/mfa/enable} (first-time
 * setup when MFA is required). Signed-in staff manage it under
 * {@code /api/me/mfa}.
 */
@RestController
public class StaffMfaController {

    public record ChallengeRequest(@NotBlank @Size(max = 100) String mfaToken) {}
    public record ChallengeCodeRequest(@NotBlank @Size(max = 100) String mfaToken, @NotBlank @Size(max = 20) String code) {}
    public record CodeRequest(@NotBlank @Size(max = 20) String code) {}

    private final StaffMfaService mfa;
    private final SessionService sessions;
    private final CurrentUser currentUser;
    private final LoginThrottle throttle;

    public StaffMfaController(StaffMfaService mfa, SessionService sessions, CurrentUser currentUser, LoginThrottle throttle) {
        this.mfa = mfa;
        this.sessions = sessions;
        this.currentUser = currentUser;
        this.throttle = throttle;
    }

    @PostMapping("/api/auth/mfa/verify")
    public Map<String, Object> verify(@Valid @RequestBody ChallengeCodeRequest body, HttpServletRequest http,
                                      HttpServletResponse response) {
        throttle.checkIp(http.getRemoteAddr());
        StaffUser s = mfa.verifyLogin(body.mfaToken(), body.code());
        return sessions.signIn(s.getId(), s.getRole(), http, response);
    }

    @PostMapping("/api/auth/mfa/setup")
    public StaffMfaService.Setup setupWithChallenge(@Valid @RequestBody ChallengeRequest body, HttpServletRequest http) {
        throttle.checkIp(http.getRemoteAddr());
        return mfa.startSetupWithChallenge(body.mfaToken());
    }

    /** Finishes first-time setup and signs in. The recovery codes are shown once. */
    @PostMapping("/api/auth/mfa/enable")
    public Map<String, Object> enableWithChallenge(@Valid @RequestBody ChallengeCodeRequest body, HttpServletRequest http,
                                                   HttpServletResponse response) {
        throttle.checkIp(http.getRemoteAddr());
        Map.Entry<StaffUser, List<String>> done = mfa.enableWithChallenge(body.mfaToken(), body.code());
        Map<String, Object> out = sessions.signIn(done.getKey().getId(), done.getKey().getRole(), http, response);
        out.put("recoveryCodes", done.getValue());
        return out;
    }

    // ------------------------------------------------------------ signed in --

    @GetMapping("/api/me/mfa")
    public Map<String, Object> status() {
        return mfa.status(staffId());
    }

    @PostMapping("/api/me/mfa/setup")
    public StaffMfaService.Setup setup() {
        return mfa.startSetup(staffId());
    }

    /** Signs out every session (including this one): sign in again with a code. */
    @PostMapping("/api/me/mfa/enable")
    public Map<String, Object> enable(@Valid @RequestBody CodeRequest body) {
        return Map.of("recoveryCodes", mfa.enable(staffId(), body.code()));
    }

    @PostMapping("/api/me/mfa/disable")
    public Map<String, Object> disable(@Valid @RequestBody CodeRequest body) {
        mfa.disable(staffId(), body.code());
        return Map.of("status", "DISABLED");
    }

    private Long staffId() {
        Role r = currentUser.role();
        if (!r.isStaff()) throw ApiException.forbidden("NOT_SUPPORTED", "Two-step sign-in is for staff accounts.");
        return currentUser.id();
    }
}
