package com.etekisi.etekisi.agent.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establishes a correlation id for every inbound request:
 *   - reads {@code X-Correlation-Id} if the client provided one
 *   - otherwise generates a fresh UUID
 *   - puts it in MDC (for log correlation) and CorrelationContext
 *   - echoes it back in the response header so the client can trace
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String inbound = req.getHeader(CorrelationContext.HEADER);
        String id = (inbound == null || inbound.isBlank()) ? UUID.randomUUID().toString() : inbound.trim();
        try {
            CorrelationContext.set(id);
            MDC.put(CorrelationContext.MDC_KEY, id);
            res.setHeader(CorrelationContext.HEADER, id);
            chain.doFilter(req, res);
        } finally {
            MDC.remove(CorrelationContext.MDC_KEY);
            CorrelationContext.clear();
        }
    }
}
