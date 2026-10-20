package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.AccountToken;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.security.LoginThrottle;
import com.etekisi.etekisi.service.AccountSecurityService;
import com.etekisi.etekisi.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Verification, password reset / change and consent.
 *
 * <p>Public (no token): {@code GET /api/legal}, {@code POST /api/auth/verify-email},
 * {@code POST /api/auth/password/forgot}, {@code POST /api/auth/password/reset}.
 * Everything under {@code /api/me} needs the commuter's or driver's token.
 */
@RestController
public class AccountSecurityController {

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {}
    public record ForgotRequest(@NotBlank @Size(max = 200) String email, @NotNull Role role, AccountToken.Channel channel) {}
    public record ResetRequest(@Size(max = 200) String token, @Size(max = 200) String email, Role role,
                               @Size(max = 12) String code, @NotBlank @Size(max = 200) String newPassword) {}
    public record CodeRequest(@NotBlank @Size(max = 12) String code) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(max = 200) String newPassword) {}
    public record TermsRequest(@NotBlank @Size(max = 40) String version) {}

    private final AccountSecurityService accounts;
    private final CurrentUser currentUser;
    private final LoginThrottle throttle;

    public AccountSecurityController(AccountSecurityService accounts, CurrentUser currentUser, LoginThrottle throttle) {
        this.accounts = accounts;
        this.currentUser = currentUser;
        this.throttle = throttle;
    }

    // ---------------------------------------------------------------- public --

    /** The version people agree to at sign-up; the documents themselves are pages on the frontend. */
    @GetMapping("/api/legal")
    public Map<String, Object> legal() {
        return Map.of("version", accounts.termsVersion(),
                "documents", Map.of("terms", "/terms", "privacy", "/privacy", "driverAgreement", "/driver-agreement"),
                "phoneVerificationAvailable", accounts.smsAvailable());
    }

    @PostMapping("/api/auth/verify-email")
    public Map<String, Object> verifyEmail(@Valid @RequestBody TokenRequest body, HttpServletRequest http) {
        throttle.checkIp(http.getRemoteAddr());
        Role role = accounts.verifyEmail(body.token());
        return Map.of("status", "VERIFIED", "role", role.name());
    }

    @PostMapping("/api/auth/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> forgot(@Valid @RequestBody ForgotRequest body, HttpServletRequest http) {
        throttle.checkIp(http.getRemoteAddr());
        AccountToken.Channel channel = body.channel() == null ? AccountToken.Channel.EMAIL : body.channel();
        accounts.forgotPassword(body.email(), body.role(), channel);
        return Map.of("status", "ACCEPTED", "message", channel == AccountToken.Channel.SMS
                ? "If an account uses that email, we've sent a code by SMS to its phone number."
                : "If an account uses that email, we've sent it a link to reset the password.");
    }

    @PostMapping("/api/auth/password/reset")
    public Map<String, Object> reset(@Valid @RequestBody ResetRequest body, HttpServletRequest http) {
        throttle.checkIp(http.getRemoteAddr());
        if (body.token() != null && !body.token().isBlank()) {
            accounts.resetWithToken(body.token(), body.newPassword());
        } else if (body.code() != null && body.email() != null && body.role() != null) {
            accounts.resetWithCode(body.email(), body.role(), body.code(), body.newPassword());
        } else {
            throw ApiException.badRequest("INVALID_REQUEST", "Send the token from the email link, or your email, role and SMS code.");
        }
        return Map.of("status", "PASSWORD_RESET", "message", "Password changed. Sign in with your new password.");
    }

    // ------------------------------------------------------------ signed in --

    @GetMapping("/api/me/status")
    public Map<String, Object> status() {
        return accounts.status(currentUser.id(), currentUser.role());
    }

    @PostMapping("/api/me/verification/email")
    public Map<String, Object> resendEmail() {
        accounts.resendEmailVerification(currentUser.id(), currentUser.role());
        return Map.of("status", "SENT");
    }

    @PostMapping("/api/me/verification/phone")
    public Map<String, Object> sendPhoneCode() {
        accounts.sendPhoneVerification(currentUser.id(), currentUser.role());
        return Map.of("status", "SENT");
    }

    @PostMapping("/api/me/verification/phone/confirm")
    public Map<String, Object> confirmPhone(@Valid @RequestBody CodeRequest body) {
        accounts.confirmPhone(currentUser.id(), currentUser.role(), body.code());
        return Map.of("status", "VERIFIED");
    }

    /** Signs out every other session; the caller signs in again with the new password. */
    @PostMapping("/api/me/password")
    public Map<String, Object> changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        accounts.changePassword(currentUser.id(), currentUser.role(), body.currentPassword(), body.newPassword());
        return Map.of("status", "PASSWORD_CHANGED");
    }

    @PostMapping("/api/me/terms")
    public Map<String, Object> acceptTerms(@Valid @RequestBody TermsRequest body) {
        accounts.acceptTerms(currentUser.id(), currentUser.role(), body.version());
        return Map.of("status", "ACCEPTED", "version", body.version());
    }
}
