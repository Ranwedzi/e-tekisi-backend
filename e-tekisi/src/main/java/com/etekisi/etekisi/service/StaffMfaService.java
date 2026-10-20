package com.etekisi.etekisi.service;

import com.etekisi.etekisi.model.StaffUser;
import com.etekisi.etekisi.repository.StaffUserRepository;
import com.etekisi.etekisi.security.FieldEncryptor;
import com.etekisi.etekisi.security.LoginThrottle;
import com.etekisi.etekisi.security.Totp;
import com.etekisi.etekisi.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-factor sign-in for operators and admins (TOTP authenticator app).
 *
 * <p>After the password is checked, staff with MFA get a one-time challenge
 * instead of a token: {@code POST /api/auth/mfa/verify} with the challenge
 * and a 6-digit code (or a recovery code) completes the sign-in. With
 * {@code tekisi.security.staff-mfa-required=true} (the prod default) staff
 * without MFA are walked through setting it up first.
 *
 * <p>Challenges live in memory for 5 minutes and allow 5 tries; like the
 * login throttle, several backend instances would need a shared store.
 */
@Service
public class StaffMfaService {

    public enum Purpose { LOGIN, SETUP }
    public record Challenge(Long staffId, Purpose purpose, Instant expiresAt, int attempts) {}
    public record Setup(String secret, String otpauthUrl) {}

    private static final Logger log = LoggerFactory.getLogger(StaffMfaService.class);
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;
    private static final int RECOVERY_CODES = 8;
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final StaffUserRepository staff;
    private final FieldEncryptor encryptor;
    private final boolean required;
    private final LoginThrottle throttle;

    public StaffMfaService(StaffUserRepository staff, FieldEncryptor encryptor, LoginThrottle throttle,
                           @Value("${tekisi.security.staff-mfa-required:false}") boolean required) {
        this.staff = staff;
        this.encryptor = encryptor;
        this.throttle = throttle;
        this.required = required;
    }

    public boolean required() {
        return required;
    }

    /** After a correct password: null to sign in now, or the challenge the next step needs. */
    public Map<String, Object> challengeFor(StaffUser s) {
        if (s.isMfaEnabled()) {
            return Map.of("status", "MFA_REQUIRED", "mfaToken", newChallenge(s.getId(), Purpose.LOGIN),
                    "message", "Enter the 6-digit code from your authenticator app.");
        }
        if (required) {
            return Map.of("status", "MFA_SETUP_REQUIRED", "mfaToken", newChallenge(s.getId(), Purpose.SETUP),
                    "message", "Staff accounts need two-step sign-in. Set up an authenticator app to continue.");
        }
        return null;
    }

    /** Completes a sign-in. Throws 401 INVALID_MFA_CODE (the challenge survives until 5 wrong tries). */
    @Transactional
    public StaffUser verifyLogin(String token, String code) {
        Challenge c = use(token, Purpose.LOGIN);
        StaffUser s = staff.findById(c.staffId()).orElseThrow(() -> invalidChallenge());
        // Wrong codes count towards the account lockout, so signing in again
        // with the password doesn't buy more guesses.
        String key = LoginThrottle.accountKey(s.getRole().name(), s.getEmail());
        throttle.checkAccount(key);
        if (!checkCode(s, code)) {
            throttle.recordFailure(key);
            fail(token, c);
        }
        challenges.remove(token);
        throttle.recordSuccess(key);
        log.info("staff.mfa.login_ok staffId={}", s.getId());
        return s;
    }

    // --------------------------------------------------------------- setup --

    /** During a required first-time setup (challenge) or from the signed-in staff member's settings. */
    @Transactional
    public Setup startSetupWithChallenge(String token) {
        Challenge c = use(token, Purpose.SETUP);
        return startSetup(c.staffId());
    }

    @Transactional
    public Setup startSetup(Long staffId) {
        StaffUser s = load(staffId);
        if (s.isMfaEnabled()) {
            throw ApiException.conflict("MFA_ALREADY_ENABLED", "Two-step sign-in is already on.");
        }
        String secret = Totp.newSecret();
        s.setMfaSecret(encryptor.encrypt(secret));
        staff.save(s);
        return new Setup(secret, Totp.otpauthUrl("e-Tekisi", s.getEmail(), secret));
    }

    @Transactional
    public Map.Entry<StaffUser, List<String>> enableWithChallenge(String token, String code) {
        Challenge c = use(token, Purpose.SETUP);
        try {
            List<String> codes = enable(c.staffId(), code);
            challenges.remove(token);
            return Map.entry(load(c.staffId()), codes);
        } catch (ApiException e) {
            if ("INVALID_MFA_CODE".equals(e.getCode())) fail(token, c);
            throw e;
        }
    }

    /** Turns MFA on once a code from the new secret checks out. Returns the recovery codes (shown once). */
    @Transactional
    public List<String> enable(Long staffId, String code) {
        StaffUser s = load(staffId);
        if (s.isMfaEnabled()) {
            throw ApiException.conflict("MFA_ALREADY_ENABLED", "Two-step sign-in is already on.");
        }
        if (s.getMfaSecret() == null) {
            throw ApiException.conflict("MFA_NOT_STARTED", "Start the setup first.");
        }
        long step = Totp.verify(encryptor.decrypt(s.getMfaSecret()), code, null, Instant.now());
        if (step < 0) throw invalidCode();
        List<String> recovery = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String rc = recoveryCode();
            recovery.add(rc);
            hashes.add(sha256(rc));
        }
        s.setMfaLastStep(step);
        s.setMfaRecoveryHashes(String.join(",", hashes));
        s.setMfaEnabledAt(Instant.now());
        s.setCredentialsChangedAt(Instant.now()); // other sessions sign in again, with MFA
        staff.save(s);
        log.info("staff.mfa.enabled staffId={}", staffId);
        return recovery;
    }

    @Transactional
    public void disable(Long staffId, String code) {
        if (required) {
            throw ApiException.conflict("MFA_REQUIRED_BY_POLICY", "Two-step sign-in is required for staff accounts.");
        }
        StaffUser s = load(staffId);
        if (!s.isMfaEnabled()) return;
        if (!checkCode(s, code)) throw invalidCode();
        s.setMfaEnabledAt(null);
        s.setMfaSecret(null);
        s.setMfaRecoveryHashes(null);
        s.setMfaLastStep(null);
        s.setCredentialsChangedAt(Instant.now());
        staff.save(s);
        log.info("staff.mfa.disabled staffId={}", staffId);
    }

    public Map<String, Object> status(Long staffId) {
        StaffUser s = load(staffId);
        int left = s.getMfaRecoveryHashes() == null || s.getMfaRecoveryHashes().isBlank() ? 0
                : s.getMfaRecoveryHashes().split(",").length;
        return Map.of("enabled", s.isMfaEnabled(), "required", required, "recoveryCodesLeft", left);
    }

    // ------------------------------------------------------------- helpers --

    /** A TOTP code (not reused) or an unused recovery code, which is then spent. */
    private boolean checkCode(StaffUser s, String code) {
        String given = code == null ? "" : code.trim().replace(" ", "").replace("-", "").toLowerCase();
        if (given.matches("\\d{6}")) {
            long step = Totp.verify(encryptor.decrypt(s.getMfaSecret()), given, s.getMfaLastStep(), Instant.now());
            if (step < 0) return false;
            s.setMfaLastStep(step);
            staff.save(s);
            return true;
        }
        if (s.getMfaRecoveryHashes() == null || given.isEmpty()) return false;
        List<String> hashes = new ArrayList<>(Arrays.asList(s.getMfaRecoveryHashes().split(",")));
        if (!hashes.remove(sha256(given))) return false;
        s.setMfaRecoveryHashes(String.join(",", hashes));
        staff.save(s);
        log.warn("staff.mfa.recovery_code_used staffId={} left={}", s.getId(), hashes.size());
        return true;
    }

    private String newChallenge(Long staffId, Purpose purpose) {
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        challenges.put(token, new Challenge(staffId, purpose, Instant.now().plus(CHALLENGE_TTL), 0));
        return token;
    }

    private Challenge use(String token, Purpose purpose) {
        Challenge c = token == null ? null : challenges.get(token);
        if (c == null || c.purpose() != purpose || c.expiresAt().isBefore(Instant.now())) {
            if (token != null) challenges.remove(token);
            throw invalidChallenge();
        }
        return c;
    }

    private void fail(String token, Challenge c) {
        if (c.attempts() + 1 >= MAX_ATTEMPTS) {
            challenges.remove(token);
            log.warn("staff.mfa.too_many_attempts staffId={}", c.staffId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_CHALLENGE_EXPIRED",
                    "Too many wrong codes. Sign in again.", null);
        }
        challenges.put(token, new Challenge(c.staffId(), c.purpose(), c.expiresAt(), c.attempts() + 1));
        throw invalidCode();
    }

    private StaffUser load(Long id) {
        return staff.findById(id).orElseThrow(() -> ApiException.notFound("Staff account"));
    }

    private String recoveryCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) sb.append('-');
            sb.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE", "That code isn't right. Check the time on your phone and try again.", null);
    }

    private static ApiException invalidChallenge() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "MFA_CHALLENGE_EXPIRED", "That sign-in has expired. Sign in again.", null);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.replace("-", "").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    void evictExpired() {
        Instant now = Instant.now();
        challenges.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }
}
