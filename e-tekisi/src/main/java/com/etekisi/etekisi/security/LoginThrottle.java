package com.etekisi.etekisi.security;

import com.etekisi.etekisi.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for sign-in and sign-up:
 * <ul>
 *   <li><b>Per account</b> (role + email): after {@code max-failures} wrong
 *       passwords within the lockout window, sign-in is refused until the
 *       window passes, even with the right password. Applies to unknown
 *       emails too, so the response doesn't reveal which accounts exist.</li>
 *   <li><b>Per IP</b>: at most {@code ip-requests-per-minute} auth requests.</li>
 * </ul>
 *
 * <p>State is in memory, which is right for the single-instance deployment
 * this project ships. Running several backend instances needs a shared
 * store (e.g. Redis) or limits at the reverse proxy instead.
 */
@Component
public class LoginThrottle {

    private record Failures(int count, Instant firstAt, Instant lastAt) {}
    private record Window(long minute, int count) {}

    private final int maxFailures;
    private final Duration lockout;
    private final int ipPerMinute;
    private final Map<String, Failures> accounts = new ConcurrentHashMap<>();
    private final Map<String, Window> ips = new ConcurrentHashMap<>();

    public LoginThrottle(@Value("${tekisi.security.login.max-failures:5}") int maxFailures,
                         @Value("${tekisi.security.login.lockout-minutes:15}") long lockoutMinutes,
                         @Value("${tekisi.security.login.ip-requests-per-minute:20}") int ipPerMinute) {
        this.maxFailures = maxFailures;
        this.lockout = Duration.ofMinutes(lockoutMinutes);
        this.ipPerMinute = ipPerMinute;
    }

    /** Counts this request against the IP's per-minute budget; 429 once it's spent. */
    public void checkIp(String ip) {
        long minute = Instant.now().getEpochSecond() / 60;
        Window w = ips.merge(ip == null ? "unknown" : ip, new Window(minute, 1),
                (old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);
        if (w.count() > ipPerMinute) {
            throw tooMany(60 - Instant.now().getEpochSecond() % 60);
        }
    }

    /** 429 while the account is locked out. */
    public void checkAccount(String accountKey) {
        Failures f = accounts.get(accountKey);
        if (f == null || f.count() < maxFailures) return;
        Instant until = f.lastAt().plus(lockout);
        long secondsLeft = Duration.between(Instant.now(), until).getSeconds();
        if (secondsLeft > 0) throw tooMany(secondsLeft);
        accounts.remove(accountKey, f);
    }

    public void recordFailure(String accountKey) {
        Instant now = Instant.now();
        accounts.merge(accountKey, new Failures(1, now, now), (old, fresh) ->
                old.firstAt().plus(lockout).isBefore(now) ? fresh : new Failures(old.count() + 1, old.firstAt(), now));
    }

    public void recordSuccess(String accountKey) {
        accounts.remove(accountKey);
    }

    public static String accountKey(String role, String email) {
        return role + ":" + (email == null ? "" : email.trim().toLowerCase());
    }

    @Scheduled(fixedDelay = 300_000)
    void evictOld() {
        Instant cutoff = Instant.now().minus(lockout);
        accounts.entrySet().removeIf(e -> e.getValue().lastAt().isBefore(cutoff));
        long minute = Instant.now().getEpochSecond() / 60;
        ips.entrySet().removeIf(e -> e.getValue().minute() < minute);
    }

    private static ApiException tooMany(long retryAfterSeconds) {
        long minutes = Math.max(1, (retryAfterSeconds + 59) / 60);
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "Too many attempts. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes."),
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }
}
