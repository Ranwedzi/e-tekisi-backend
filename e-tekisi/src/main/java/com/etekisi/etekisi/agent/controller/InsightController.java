package com.etekisi.etekisi.agent.controller;

import com.etekisi.etekisi.agent.insight.InsightCode;
import com.etekisi.etekisi.agent.insight.OperationalInsight;
import com.etekisi.etekisi.agent.insight.OperationalInsightRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase 3.7 — read-only operator feed over {@code OperationalInsight} rows.
 *
 * <p>SecurityConfig restricts {@code /api/insights/**} to the OPERATOR /
 * ADMIN roles, so this controller never enforces auth itself.
 *
 * <p>Pagination is hard-capped at 200 per page so a misbehaving client can't
 * pull the entire audit trail in one call. The default page size of 50
 * matches typical OpsConsole table widths.
 *
 * <p>Anti-injection: all filter values are bound through repository
 * parameters (no string concatenation), and {@code code} / {@code severity}
 * resolve through enum {@code valueOf} which throws on unknown input — the
 * global {@code @RestControllerAdvice} maps that to RFC 7807 400.
 */
@RestController
@RequestMapping("/api/insights")
public class InsightController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final OperationalInsightRepository repo;

    public InsightController(OperationalInsightRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) Long bookingId,
            @RequestParam(required = false) Instant since,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        if (page < 0) page = 0;
        if (size <= 0) size = DEFAULT_PAGE_SIZE;
        if (size > MAX_PAGE_SIZE) size = MAX_PAGE_SIZE;

        OperationalInsight.Severity sev = severity == null ? null
                : OperationalInsight.Severity.valueOf(severity);
        InsightCode codeEnum = code == null ? null : InsightCode.valueOf(code);

        Page<OperationalInsight> result = repo.findFiltered(
                sev, codeEnum, bookingId, since,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Map<String, Object>> items = result.getContent().stream()
                .map(this::project)
                .toList();

        return Map.of(
                "items", items,
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        );
    }

    /**
     * Compact projection — never returns the JPA entity directly so that
     * accidental schema additions don't leak through the API.
     */
    private Map<String, Object> project(OperationalInsight i) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("type", i.getType().name());
        m.put("code", i.getCode().name());
        m.put("severity", i.getSeverity().name());
        m.put("bookingId", i.getBookingId());
        m.put("driverId", i.getDriverId());
        m.put("commuterId", i.getCommuterId());
        m.put("message", i.getMessage());
        m.put("correlationId", i.getCorrelationId());
        m.put("createdAt", i.getCreatedAt());
        return m;
    }
}
