package com.etekisi.etekisi.agent.payment;

import com.etekisi.etekisi.agent.audit.DecisionAuditLogger;
import com.etekisi.etekisi.agent.dto.PaymentRiskDecision;
import com.etekisi.etekisi.agent.dto.ReasonCode;
import com.etekisi.etekisi.agent.guardrail.DecisionContext;
import com.etekisi.etekisi.agent.guardrail.PolicyEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Phase 3.6 — deterministic payment-risk agent.
 *
 * <p>Inputs are bounded scalars (amount, available balance, recent activity
 * count). Output is a {@link PaymentRiskDecision} carrying its own
 * confidence, reason codes, and audit metadata. The action is constrained by
 * {@link PolicyEngine#constrainPaymentAction(PaymentRiskDecision)} — the
 * agent NEVER blocks autonomously; the engine downgrades BLOCK / REJECT
 * intents to HOLD_FOR_REVIEW.
 *
 * <p>Inputs the agent does NOT use (and therefore cannot hallucinate over):
 * the user's intent, free-text memos, geolocation. Risk is purely a
 * function of the trusted scalars below.
 *
 * <p>Wallet / payout state remains owned by the authoritative service; the
 * agent only emits a recommendation that those services may consult.
 */
@Service
public class PaymentRiskAgent {

    public static final String NAME = "PaymentRiskAgent";
    public static final String VERSION = "1.0.0-rules";

    private final PolicyEngine policy;
    private final DecisionAuditLogger audit;

    private final double highAmount;
    private final double veryHighAmount;
    private final int newUserTripsThreshold;
    private final int velocityThreshold;

    public PaymentRiskAgent(PolicyEngine policy,
                            DecisionAuditLogger audit,
                            @Value("${tekisi.agent.payment.high-amount-rand:1500}") double highAmount,
                            @Value("${tekisi.agent.payment.very-high-amount-rand:5000}") double veryHighAmount,
                            @Value("${tekisi.agent.payment.new-user-trips:3}") int newUserTripsThreshold,
                            @Value("${tekisi.agent.payment.velocity-threshold:5}") int velocityThreshold) {
        this.policy = policy;
        this.audit = audit;
        this.highAmount = highAmount;
        this.veryHighAmount = veryHighAmount;
        this.newUserTripsThreshold = newUserTripsThreshold;
        this.velocityThreshold = velocityThreshold;
    }

    /**
     * Evaluate a wallet top-up.
     *
     * @param subjectId       the wallet user id
     * @param amount          rands being topped up
     * @param userTripsToDate count of completed trips for this user
     * @param recent24hCount  count of top-ups in the last 24h
     */
    public PaymentRiskDecision evaluateTopUp(Long subjectId,
                                             double amount,
                                             int userTripsToDate,
                                             int recent24hCount,
                                             String correlationId) {
        DecisionContext ctx = new DecisionContext(NAME, VERSION, correlationId)
                .consulted("wallet")
                .consulted("user.trips_completed");

        if (amount <= 0) {
            ctx.addReason(ReasonCode.SCHEMA_INVALID).withConfidence(1.0);
            return publish(new PaymentRiskDecision(subjectId,
                    PaymentRiskDecision.SubjectType.BOOKING,
                    PaymentRiskDecision.RiskLevel.LOW,
                    PaymentRiskDecision.Action.ALLOW,
                    ctx.seal()));
        }
        return scoreCommon(subjectId, PaymentRiskDecision.SubjectType.BOOKING,
                amount, userTripsToDate, recent24hCount, /*availableBalance=*/Double.POSITIVE_INFINITY, ctx);
    }

    /**
     * Evaluate a payout request.
     *
     * @param subjectId        the payout request id
     * @param amount           rands being paid out
     * @param availableBalance driver's current balance
     * @param recent24hCount   count of payout requests in the last 24h
     */
    public PaymentRiskDecision evaluatePayout(Long subjectId,
                                              double amount,
                                              double availableBalance,
                                              int driverTripsToDate,
                                              int recent24hCount,
                                              String correlationId) {
        DecisionContext ctx = new DecisionContext(NAME, VERSION, correlationId)
                .consulted("driver.balance")
                .consulted("driver.trips_completed");
        return scoreCommon(subjectId, PaymentRiskDecision.SubjectType.PAYOUT_REQUEST,
                amount, driverTripsToDate, recent24hCount, availableBalance, ctx);
    }

    private PaymentRiskDecision scoreCommon(Long subjectId,
                                            PaymentRiskDecision.SubjectType type,
                                            double amount,
                                            int tripsToDate,
                                            int recent24hCount,
                                            double availableBalance,
                                            DecisionContext ctx) {
        // Insufficient funds is an objective signal, not a risk score.
        if (availableBalance < amount) {
            ctx.addReason(ReasonCode.PAYMENT_INSUFFICIENT_FUNDS).withConfidence(1.0);
            return publish(new PaymentRiskDecision(subjectId, type,
                    PaymentRiskDecision.RiskLevel.HIGH,
                    PaymentRiskDecision.Action.HOLD_FOR_REVIEW,
                    ctx.seal()));
        }

        int score = 0;
        if (amount >= veryHighAmount) {
            ctx.addReason(ReasonCode.PAYMENT_AMOUNT_VERY_HIGH);
            score += 3;
        } else if (amount >= highAmount) {
            ctx.addReason(ReasonCode.PAYMENT_AMOUNT_HIGH);
            score += 2;
        } else {
            ctx.addReason(ReasonCode.PAYMENT_AMOUNT_NORMAL);
        }

        if (tripsToDate < newUserTripsThreshold) {
            ctx.addReason(ReasonCode.PAYMENT_NEW_USER);
            score += 1;
        }

        if (recent24hCount >= velocityThreshold) {
            ctx.addReason(ReasonCode.PAYMENT_VELOCITY_HIGH);
            score += 2;
        }

        PaymentRiskDecision.RiskLevel level;
        PaymentRiskDecision.Action proposed;
        double confidence;
        if (score >= 4) {
            level = PaymentRiskDecision.RiskLevel.BLOCK;
            proposed = PaymentRiskDecision.Action.REJECT_WITH_REVIEW;
            confidence = 0.85;
        } else if (score >= 2) {
            level = PaymentRiskDecision.RiskLevel.HIGH;
            proposed = PaymentRiskDecision.Action.HOLD_FOR_REVIEW;
            confidence = 0.75;
        } else if (score >= 1) {
            level = PaymentRiskDecision.RiskLevel.MEDIUM;
            proposed = PaymentRiskDecision.Action.ALLOW;
            confidence = 0.7;
        } else {
            level = PaymentRiskDecision.RiskLevel.LOW;
            proposed = PaymentRiskDecision.Action.ALLOW;
            confidence = 0.9;
        }
        ctx.withConfidence(confidence);

        PaymentRiskDecision proposedDecision =
                new PaymentRiskDecision(subjectId, type, level, proposed, ctx.seal());
        PaymentRiskDecision.Action constrained = policy.constrainPaymentAction(proposedDecision);
        return publish(new PaymentRiskDecision(subjectId, type, level, constrained,
                proposedDecision.metadata()));
    }

    private PaymentRiskDecision publish(PaymentRiskDecision d) {
        audit.record(d.metadata(),
                "subjectId=" + d.subjectId() + " type=" + d.subjectType() + " level=" + d.level(),
                d);
        return d;
    }
}
