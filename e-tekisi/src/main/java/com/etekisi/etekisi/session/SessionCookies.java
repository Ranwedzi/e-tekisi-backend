package com.etekisi.etekisi.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The two session cookies. Both are httpOnly (page scripts can't read them),
 * Secure and SameSite=Strict (never sent from another site, so no cross-site
 * request can use them).
 * <ul>
 *   <li>access: the JWT, sent with every API call ({@code __Host-} prefix:
 *       this host only, path /).</li>
 *   <li>refresh: sent only to {@value #REFRESH_PATH}.</li>
 * </ul>
 * With {@code tekisi.session.secure-cookies=false} (plain-http testing on
 * something other than localhost) the prefixes, which require Secure, are dropped.
 */
@Component
public class SessionCookies {

    public static final String REFRESH_PATH = "/api/auth/session";

    private final boolean secure;
    private final String accessName;
    private final String refreshName;

    public SessionCookies(@Value("${tekisi.session.secure-cookies:true}") boolean secure) {
        this.secure = secure;
        this.accessName = secure ? "__Host-tekisi_at" : "tekisi_at";
        this.refreshName = secure ? "__Secure-tekisi_rt" : "tekisi_rt";
    }

    public String access(HttpServletRequest req) {
        return read(req, accessName);
    }

    public String refresh(HttpServletRequest req) {
        return read(req, refreshName);
    }

    /** True when the request carries either session cookie (so it may be authenticated by cookie). */
    public boolean present(HttpServletRequest req) {
        return access(req) != null || refresh(req) != null;
    }

    public void write(HttpServletResponse res, String accessToken, Duration accessTtl, String refreshToken, Duration refreshTtl) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(accessName, accessToken, "/", accessTtl).toString());
        if (refreshToken != null) {
            res.addHeader(HttpHeaders.SET_COOKIE, cookie(refreshName, refreshToken, REFRESH_PATH, refreshTtl).toString());
        }
    }

    public void clear(HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(accessName, "", "/", Duration.ZERO).toString());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(refreshName, "", REFRESH_PATH, Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAge)
                .build();
    }

    private static String read(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) return c.getValue();
        }
        return null;
    }
}
