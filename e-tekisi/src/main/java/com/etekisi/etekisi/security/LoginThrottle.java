package com.etekisi.etekisi.security;

import com.etekisi.etekisi.cluster.SharedState;
import com.etekisi.etekisi.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Brute-force protection for sign-in and sign-up:
 * <ul>
 *   <li><b>Per account</b> (role + email): after {@code max-failures} wrong
 *       passwords (or MFA codes), each within the lockout window of the last,
 *       sign-in is refused until the window passes, even with the right
 *       password. Applies to unknown emails too, so the response doesn't
 *       reveal which accounts exist.</li>
 *   <li><b>Per IP</b>: at most {@code ip-requests-per-minute} auth requests.</li>
 * </ul>
 *
 * <p>Counters live in {@link SharedState}: in memory for one instance, in
 * Redis (REDIS_ENABLED) when several share the load.
 */
@Component
public class LoginThrottle {

    private final int maxFailures;
    private final Duration lockout;
    private final int ipPerMinute;
    private final SharedState state;

    public LoginThrottle(SharedState state,
                         @Value("${tekisi.security.login.max-failures:5}") int maxFailures,
                         @Value("${tekisi.security.login.lockout-minutes:15}") long lockoutMinutes,
                         @Value("${tekisi.security.login.ip-requests-per-minute:20}") int ipPerMinute) {
        this.state = state;
        this.maxFailures = maxFailures;
        this.lockout = Duration.ofMinutes(lockoutMinutes);
        this.ipPerMinute = ipPerMinute;
    }

    /** Counts this request against the IP's per-minute budget; 429 once it's spent. */
    public void checkIp(String ip) {
        long now = Instant.now().getEpochSecond();
        long count = state.increment("throttle:ip:" + (ip == null ? "unknown" : ip) + ":" + (now / 60), Duration.ofMinutes(2));
        if (count > ipPerMinute) {
            throw tooMany(60 - now % 60);
        }
    }

    /** 429 while the account is locked out. */
    public void checkAccount(String accountKey) {
        String key = accountKeyFor(accountKey);
        long failures = state.get(key).map(Long::parseLong).orElse(0L);
        if (failures < maxFailures) return;
        long secondsLeft = state.ttl(key).toSeconds();
        if (secondsLeft > 0) throw tooMany(secondsLeft);
    }

    public void recordFailure(String accountKey) {
        state.incrementSliding(accountKeyFor(accountKey), lockout);
    }

    public void recordSuccess(String accountKey) {
        state.delete(accountKeyFor(accountKey));
    }

    public static String accountKey(String role, String email) {
        return role + ":" + (email == null ? "" : email.trim().toLowerCase());
    }

    private static String accountKeyFor(String accountKey) {
        return "throttle:account:" + accountKey;
    }

    private static ApiException tooMany(long retryAfterSeconds) {
        long minutes = Math.max(1, (retryAfterSeconds + 59) / 60);
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                "Too many attempts. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes."),
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }
}
