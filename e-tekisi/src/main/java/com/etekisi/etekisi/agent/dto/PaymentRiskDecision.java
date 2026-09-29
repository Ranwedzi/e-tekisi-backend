package com.etekisi.etekisi.agent.dto;

public record PaymentRiskDecision(
        Long subjectId,                // bookingId or payoutRequestId
        SubjectType subjectType,
        RiskLevel level,
        Action action,
        DecisionMetadata metadata
) {
    public enum SubjectType { BOOKING, PAYOUT_REQUEST }
    public enum RiskLevel { LOW, MEDIUM, HIGH, BLOCK }
    /** Recommendation only — wallet/payout state is owned by the authoritative service. */
    public enum Action { ALLOW, HOLD_FOR_REVIEW, REJECT_WITH_REVIEW }
}
