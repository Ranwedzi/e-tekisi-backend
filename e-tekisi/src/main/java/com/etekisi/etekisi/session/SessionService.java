package com.etekisi.etekisi.session;

import com.etekisi.etekisi.config.JwtTokenService;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.AccountState;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.StaffState;
import com.etekisi.etekisi.repository.StaffUserRepository;
import com.etekisi.etekisi.repository.UserRepository;
import com.etekisi.etekisi.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Browser sessions: sign-in puts a 15-minute access token and a refresh token
 * in httpOnly cookies (see {@link SessionCookies}); the page never sees either.
 *
 * <ul>
 *   <li>Refresh replaces the refresh token. A used token presented again
 *       (after a short grace for two tabs refreshing at once) means it was
 *       copied: the whole session is revoked.</li>
 *   <li>A session ends after {@code refresh-days} without use, at most
 *       {@code max-days} after sign-in; staff sessions after
 *       {@code staff-hours}, however active.</li>
 *   <li>A password change, reset or account deletion ends every session at
 *       the next refresh (and every access token at once, through
 *       AccountStateTokenValidator).</li>
 * </ul>
 * Clients that don't ask for cookies ({@code X-Session: cookie}) keep getting
 * a Bearer token in the response body, as before.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    public static final String MODE_HEADER = "X-Session";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final SessionCookies cookies;
    private final JwtTokenService jwt;
    private final UserRepository users;
    private final DriverRepository drivers;
    private final StaffUserRepository staff;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Duration maxAge;
    private final Duration staffTtl;
    private final Duration reuseGrace;

    public SessionService(RefreshTokenRepository tokens, SessionCookies cookies, JwtTokenService jwt,
                          UserRepository users, DriverRepository drivers, StaffUserRepository staff,
                          @Value("${tekisi.session.access-minutes:15}") long accessMinutes,
                          @Value("${tekisi.session.refresh-days:30}") long refreshDays,
                          @Value("${tekisi.session.max-days:90}") long maxDays,
                          @Value("${tekisi.session.staff-hours:12}") long staffHours,
                          @Value("${tekisi.session.reuse-grace-seconds:20}") long graceSeconds) {
        this.tokens = tokens;
        this.cookies = cookies;
        this.jwt = jwt;
        this.users = users;
        this.drivers = drivers;
        this.staff = staff;
        this.accessTtl = Duration.ofMinutes(accessMinutes);
        this.refreshTtl = Duration.ofDays(refreshDays);
        this.maxAge = Duration.ofDays(maxDays);
        this.staffTtl = Duration.ofHours(staffHours);
        this.reuseGrace = Duration.ofSeconds(graceSeconds);
    }

    public static boolean cookieMode(HttpServletRequest req) {
        return "cookie".equalsIgnoreCase(req.getHeader(MODE_HEADER));
    }

    /** The response to a successful sign-in: cookies for browsers that ask, else a Bearer token. */
    @Transactional
    public Map<String, Object> signIn(Long userId, Role role, HttpServletRequest req, HttpServletResponse res) {
        if (!cookieMode(req)) return jwt.loginBody(userId, role.name());
        Instant now = Instant.now();
        RefreshToken t = new RefreshToken();
        t.setFamilyId(UUID.randomUUID().toString());
        t.setOwnerRole(role);
        t.setOwnerId(userId);
        t.setFamilyCreatedAt(now);
        t.setUserAgent(userAgent(req));
        String raw = issue(t, now);
        cookies.write(res, jwt.mint(userId, role.name(), accessTtl), accessTtl, raw, Duration.between(now, t.getExpiresAt()));
        log.info("session.started role={} id={}", role, userId);
        return body(userId, role, t.getExpiresAt());
    }

    /** Exchanges the refresh cookie for new cookies. 401 SESSION_ENDED when it can't be used. */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> refresh(HttpServletRequest req, HttpServletResponse res) {
        String raw = cookies.refresh(req);
        if (raw == null) throw ended(res, "No session.");
        RefreshToken t = tokens.findForUpdate(hash(raw)).orElse(null);
        Instant now = Instant.now();
        if (t == null || t.getRevokedAt() != null || !t.getExpiresAt().isAfter(now)) throw ended(res, "The session has ended.");
        if (!accountAllows(t)) {
            tokens.revokeFamily(t.getFamilyId(), now);
            throw ended(res, "The password was changed or the account closed.");
        }
        if (t.getUsedAt() != null) {
            if (t.getUsedAt().plus(reuseGrace).isAfter(now)) {
                // Another tab refreshed with the same cookie a moment ago and the
                // browser already holds the new refresh token: just a new access token.
                cookies.write(res, jwt.mint(t.getOwnerId(), t.getOwnerRole().name(), accessTtl), accessTtl, null, null);
                return body(t.getOwnerId(), t.getOwnerRole(), t.getExpiresAt());
            }
            tokens.revokeFamily(t.getFamilyId(), now);
            log.warn("session.refresh_reuse role={} id={} family={}: a used refresh token came back; session revoked",
                    t.getOwnerRole(), t.getOwnerId(), t.getFamilyId());
            throw ended(res, "The session has ended.");
        }
        t.setUsedAt(now);
        RefreshToken next = new RefreshToken();
        next.setFamilyId(t.getFamilyId());
        next.setOwnerRole(t.getOwnerRole());
        next.setOwnerId(t.getOwnerId());
        next.setFamilyCreatedAt(t.getFamilyCreatedAt());
        next.setUserAgent(userAgent(req));
        String nextRaw = issue(next, now);
        cookies.write(res, jwt.mint(t.getOwnerId(), t.getOwnerRole().name(), accessTtl), accessTtl,
                nextRaw, Duration.between(now, next.getExpiresAt()));
        return body(t.getOwnerId(), t.getOwnerRole(), next.getExpiresAt());
    }

    /** Ends this browser's session (whatever state it's in) and clears the cookies. */
    @Transactional
    public void signOut(HttpServletRequest req, HttpServletResponse res) {
        String raw = cookies.refresh(req);
        if (raw != null) {
            tokens.findByTokenHash(hash(raw)).ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), Instant.now()));
        }
        cookies.clear(res);
    }

    public record Device(String device, Instant signedInAt, Instant lastActiveAt) {}

    /** The account's sessions, newest activity first. */
    @Transactional(readOnly = true)
    public List<Device> devices(Role role, Long ownerId) {
        return tokens.findLive(role, ownerId, Instant.now()).stream()
                .map(t -> new Device(describe(t.getUserAgent()), t.getFamilyCreatedAt(), t.getCreatedAt()))
                .toList();
    }

    /** Ends every session of the account; access tokens are cut off by the caller bumping credentialsChangedAt. */
    @Transactional
    public int revokeAll(Role role, Long ownerId) {
        return tokens.revokeAll(role, ownerId, Instant.now());
    }

    @SchedulerLock(name = "session-cleanup", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${tekisi.session.cleanup-cron:0 50 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int n = tokens.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(1)));
        if (n > 0) log.info("session.purged count={}", n);
    }

    // ------------------------------------------------------------------------

    private String issue(RefreshToken t, Instant now) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        boolean isStaff = t.getOwnerRole() == Role.OPERATOR || t.getOwnerRole() == Role.ADMIN;
        Instant idle = now.plus(isStaff ? staffTtl : refreshTtl);
        Instant hardStop = t.getFamilyCreatedAt().plus(isStaff ? staffTtl : maxAge);
        t.setTokenHash(hash(raw));
        t.setCreatedAt(now);
        t.setExpiresAt(idle.isBefore(hardStop) ? idle : hardStop);
        tokens.save(t);
        return raw;
    }

    private boolean accountAllows(RefreshToken t) {
        Instant changed;
        switch (t.getOwnerRole()) {
            case COMMUTER, DRIVER -> {
                Optional<AccountState> s = t.getOwnerRole() == Role.COMMUTER
                        ? users.findStateById(t.getOwnerId()) : drivers.findStateById(t.getOwnerId());
                if (s.isEmpty() || s.get().getDeletedAt() != null) return false;
                changed = s.get().getCredentialsChangedAt();
            }
            case OPERATOR, ADMIN -> {
                Optional<StaffState> s = staff.findStateById(t.getOwnerId());
                if (s.isEmpty()) return false;
                changed = s.get().getCredentialsChangedAt();
            }
            default -> {
                return false;
            }
        }
        // Same second as the change = the sign-in that followed it (as for access tokens).
        return changed == null || !t.getFamilyCreatedAt().truncatedTo(ChronoUnit.SECONDS)
                .isBefore(changed.truncatedTo(ChronoUnit.SECONDS));
    }

    private Map<String, Object> body(Long userId, Role role, Instant sessionExpiresAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "SUCCESS");
        body.put("userId", userId);
        body.put("role", role.name());
        body.put("expiresInSeconds", accessTtl.toSeconds());
        body.put("sessionExpiresInSeconds", Math.max(0, Duration.between(Instant.now(), sessionExpiresAt).toSeconds()));
        return body;
    }

    private ApiException ended(HttpServletResponse res, String detail) {
        cookies.clear(res);
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_ENDED", detail, null);
    }

    private static String userAgent(HttpServletRequest req) {
        String ua = req.getHeader(HttpHeaders.USER_AGENT);
        if (ua == null) return null;
        return ua.length() > 200 ? ua.substring(0, 200) : ua;
    }

    /** "Chrome on Android", good enough to recognise a device in a list. */
    static String describe(String ua) {
        if (ua == null || ua.isBlank()) return "Unknown device";
        String s = ua.toLowerCase(Locale.ROOT);
        String browser = s.contains("edg/") ? "Edge" : s.contains("samsungbrowser") ? "Samsung Internet"
                : s.contains("firefox") ? "Firefox" : s.contains("chrome") ? "Chrome"
                : s.contains("safari") ? "Safari" : "Browser";
        String os = s.contains("android") ? "Android" : s.contains("iphone") || s.contains("ipad") ? "iPhone/iPad"
                : s.contains("windows") ? "Windows" : s.contains("mac os") ? "Mac"
                : s.contains("linux") ? "Linux" : null;
        return os == null ? browser : browser + " on " + os;
    }

    static String hash(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
