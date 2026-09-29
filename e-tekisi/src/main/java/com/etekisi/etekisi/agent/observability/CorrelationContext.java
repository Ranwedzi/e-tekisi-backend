package com.etekisi.etekisi.agent.observability;

import java.util.UUID;

/**
 * Thread-local correlation id used to thread a single trace through:
 *   inbound HTTP -> controller -> agent -> event listeners -> audit row
 *
 * Always set/cleared by {@link CorrelationIdFilter} on the request thread.
 * For background work, callers pass the correlation id explicitly into
 * agent methods so it is preserved off-thread.
 */
public final class CorrelationContext {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {}

    public static void set(String value) { CURRENT.set(value); }
    public static void clear() { CURRENT.remove(); }

    public static String get() { return CURRENT.get(); }

    /** Returns the current correlation id or generates a fresh one. */
    public static String getOrCreate() {
        String v = CURRENT.get();
        if (v != null && !v.isBlank()) return v;
        String fresh = UUID.randomUUID().toString();
        CURRENT.set(fresh);
        return fresh;
    }
}
