package com.etekisi.etekisi.agent.guardrail;

import com.etekisi.etekisi.agent.dto.DispatchDecision;
import com.etekisi.etekisi.agent.dto.PaymentRiskDecision;
import com.etekisi.etekisi.agent.dto.SafetyAlert;
import org.springframework.stereotype.Component;

/**
 * Centralised authorisation rules.
 *
 * Agents PROPOSE a decision; PolicyEngine AUTHORISES it. This keeps
 * cross-cutting safety/regulatory rules in one auditable place and stops
 * any single agent from quietly bypassing them.
 */
@Component
public class PolicyEngine {

    /** A dispatch decision is only autonomous if assigned + non-degraded + confident enough. */
    public boolean isDispatchAutonomous(DispatchDecision decision) {
        return decision.status() == DispatchDecision.Status.ASSIGNED
                && !decision.metadata().degraded()
                && !decision.metadata().fallbackUsed();
    }

    /** Critical safety alerts NEVER trigger external escalation autonomously. */
    public SafetyAlert.Action constrainSafetyAction(SafetyAlert proposed) {
        if (proposed.severity() == SafetyAlert.Severity.CRITICAL) {
            return SafetyAlert.Action.HUMAN_REVIEW_REQUIRED;
        }
        return proposed.recommendedAction();
    }

    /** Block-level payout actions require human review — never auto-reject. */
    public PaymentRiskDecision.Action constrainPaymentAction(PaymentRiskDecision proposed) {
        if (proposed.level() == PaymentRiskDecision.RiskLevel.BLOCK
                || proposed.action() == PaymentRiskDecision.Action.REJECT_WITH_REVIEW) {
            return PaymentRiskDecision.Action.HOLD_FOR_REVIEW;
        }
        return proposed.action();
    }
}
