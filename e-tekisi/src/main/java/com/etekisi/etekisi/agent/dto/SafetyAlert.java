package com.etekisi.etekisi.agent.dto;

public record SafetyAlert(
        Long bookingId,
        Severity severity,
        AlertType type,
        Action recommendedAction,
        DecisionMetadata metadata
) {
    public enum Severity { INFO, WARN, CRITICAL }
    public enum AlertType {
        NONE,
        ROUTE_DEVIATION,
        PROLONGED_STATIONARY,
        PANIC_BUTTON,
        INSUFFICIENT_DATA
    }
    /**
     * Recommended action only — execution is delegated to OpsConsole / notification
     * service. The agent NEVER autonomously contacts authorities.
     */
    public enum Action {
        NONE,
        NOTIFY_USER,
        NOTIFY_TRUSTED_CONTACTS,
        OPS_REVIEW,
        HUMAN_REVIEW_REQUIRED
    }
}
