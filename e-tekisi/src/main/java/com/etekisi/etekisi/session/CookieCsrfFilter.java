package com.etekisi.etekisi.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * CSRF protection for cookie sessions, on top of SameSite=Strict: a request
 * that changes something and is authenticated by cookie must carry
 * {@code X-Requested-With}. Browsers only let another site add that header
 * after a CORS preflight, which only our own origins pass. Bearer-token
 * requests aren't affected (another site can't attach the header).
 */
public class CookieCsrfFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Requested-With";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final SessionCookies cookies;

    public CookieCsrfFilter(SessionCookies cookies) {
        this.cookies = cookies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        boolean needsCheck = !SAFE.contains(req.getMethod())
                && req.getHeader(HttpHeaders.AUTHORIZATION) == null
                && cookies.present(req);
        String h = req.getHeader(HEADER);
        if (needsCheck && (h == null || h.isBlank())) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/problem+json");
            res.getWriter().write("""
                    {"type":"about:blank","title":"Forbidden","status":403,"code":"CSRF_HEADER_MISSING",\
                    "detail":"Requests signed in with a cookie must send the X-Requested-With header."}""");
            return;
        }
        chain.doFilter(req, res);
    }
}
