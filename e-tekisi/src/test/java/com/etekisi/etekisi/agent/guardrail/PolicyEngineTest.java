package com.etekisi.etekisi.agent.guardrail;

import com.etekisi.etekisi.agent.dto.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEngineTest {

    private final PolicyEngine policy = new PolicyEngine();

    @Test
    void degradedDispatchIsNeverAutonomous() {
        DispatchDecision d = new DispatchDecision(1L,
                DispatchDecision.Status.ASSIGNED, 7L, List.of(),
                meta(0.9, true, false));
        assertThat(policy.isDispatchAutonomous(d)).isFalse();
    }

    @Test
    void fallbackDispatchIsNeverAutonomous() {
        DispatchDecision d = new DispatchDecision(1L,
                DispatchDecision.Status.ASSIGNED, 7L, List.of(),
                meta(0.9, false, true));
        assertThat(policy.isDispatchAutonomous(d)).isFalse();
    }

    @Test
    void noMatchIsNeverAutonomous() {
        DispatchDecision d = new DispatchDecision(1L,
                DispatchDecision.Status.NO_MATCH, null, List.of(),
                meta(0.9, false, false));
        assertThat(policy.isDispatchAutonomous(d)).isFalse();
    }

    @Test
    void cleanAssignmentIsAutonomous() {
        DispatchDecision d = new DispatchDecision(1L,
                DispatchDecision.Status.ASSIGNED, 7L, List.of(),
                meta(0.9, false, false));
        assertThat(policy.isDispatchAutonomous(d)).isTrue();
    }

    @Test
    void criticalSafetyAlwaysRoutesToHumanReview() {
        SafetyAlert proposed = new SafetyAlert(1L,
                SafetyAlert.Severity.CRITICAL,
                SafetyAlert.AlertType.ROUTE_DEVIATION,
                SafetyAlert.Action.OPS_REVIEW,
                meta(0.9, false, false));
        assertThat(policy.constrainSafetyAction(proposed))
                .isEqualTo(SafetyAlert.Action.HUMAN_REVIEW_REQUIRED);
    }

    @Test
    void warnSafetyKeepsItsAction() {
        SafetyAlert proposed = new SafetyAlert(1L,
                SafetyAlert.Severity.WARN,
                SafetyAlert.AlertType.PROLONGED_STATIONARY,
                SafetyAlert.Action.NOTIFY_USER,
                meta(0.7, false, false));
        assertThat(policy.constrainSafetyAction(proposed))
                .isEqualTo(SafetyAlert.Action.NOTIFY_USER);
    }

    @Test
    void blockPaymentIsDowngradedToHold() {
        PaymentRiskDecision proposed = new PaymentRiskDecision(1L,
                PaymentRiskDecision.SubjectType.PAYOUT_REQUEST,
                PaymentRiskDecision.RiskLevel.BLOCK,
                PaymentRiskDecision.Action.REJECT_WITH_REVIEW,
                meta(0.95, false, false));
        assertThat(policy.constrainPaymentAction(proposed))
                .isEqualTo(PaymentRiskDecision.Action.HOLD_FOR_REVIEW);
    }

    private DecisionMetadata meta(double c, boolean degraded, boolean fallback) {
        return DecisionMetadata.of("t", "1", 1L, c,
                List.of(ReasonCode.OK), List.of("s"),
                fallback, degraded, "corr");
    }
}
