package com.etekisi.etekisi.service;

import com.etekisi.etekisi.agent.dto.PaymentRiskDecision;
import com.etekisi.etekisi.agent.insight.InsightCode;
import com.etekisi.etekisi.agent.insight.OperationalInsight;
import com.etekisi.etekisi.agent.insight.OperationalInsightRepository;
import com.etekisi.etekisi.agent.observability.CorrelationContext;
import com.etekisi.etekisi.agent.payment.PaymentRiskAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Runs the {@link PaymentRiskAgent} for top-ups and payouts and records a
 * PAYMENT_RISK_HOLD insight when it flags one. Never throws. For top-ups it
 * is advisory (the hard limits in MoneyLimits apply); a flagged payout is
 * held until an operator confirms they checked it.
 */
@Service
public class PaymentRiskReviewer {

    private static final Logger log = LoggerFactory.getLogger(PaymentRiskReviewer.class);

    private final ObjectProvider<PaymentRiskAgent> riskAgent;
    private final ObjectProvider<OperationalInsightRepository> insights;

    public PaymentRiskReviewer(ObjectProvider<PaymentRiskAgent> riskAgent,
                               ObjectProvider<OperationalInsightRepository> insights) {
        this.riskAgent = riskAgent;
        this.insights = insights;
    }

    public void reviewTopUp(Long commuterId, double amount, int priorTrips, int recent24hCount) {
        PaymentRiskAgent agent = riskAgent.getIfAvailable();
        if (agent == null) return;
        try {
            PaymentRiskDecision d = agent.evaluateTopUp(commuterId, amount, priorTrips, recent24hCount,
                    CorrelationContext.getOrCreate());
            recordIfFlagged(d, commuterId, null,
                    "Top-up flagged commuterId=" + commuterId + " amount=" + amount);
        } catch (RuntimeException ex) {
            log.warn("agent.payment.topup.eval_failed commuterId={} error={}", commuterId, ex.getMessage());
        }
    }

    /** The reasons, when the agent says to hold the payout for review; empty to let it through. */
    public java.util.Optional<String> reviewPayout(Long driverId, Long payoutRequestId, double amount, double balanceBefore,
                                                   int priorTrips, int recent24hCount) {
        PaymentRiskAgent agent = riskAgent.getIfAvailable();
        if (agent == null) return java.util.Optional.empty();
        try {
            PaymentRiskDecision d = agent.evaluatePayout(payoutRequestId, amount, balanceBefore, priorTrips,
                    recent24hCount, CorrelationContext.getOrCreate());
            recordIfFlagged(d, null, driverId,
                    "Payout flagged payoutRequestId=" + payoutRequestId + " amount=" + amount);
            if (isHold(d)) {
                return java.util.Optional.of(d.level() + ": " + String.join(", ", d.metadata().reasonCodes().stream()
                        .filter(r -> r != com.etekisi.etekisi.agent.dto.ReasonCode.PAYMENT_AMOUNT_NORMAL)
                        .map(r -> r.name().replace("PAYMENT_", "").replace('_', ' ').toLowerCase()).toList()));
            }
        } catch (RuntimeException ex) {
            // The agent failing must not block a payout; operators still approve every one by hand.
            log.warn("agent.payment.payout.eval_failed driverId={} error={}", driverId, ex.getMessage());
        }
        return java.util.Optional.empty();
    }

    private static boolean isHold(PaymentRiskDecision d) {
        return d.action() == PaymentRiskDecision.Action.HOLD_FOR_REVIEW
                || d.action() == PaymentRiskDecision.Action.REJECT_WITH_REVIEW;
    }

    private void recordIfFlagged(PaymentRiskDecision d, Long commuterId, Long driverId, String summary) {
        if (d.action() != PaymentRiskDecision.Action.HOLD_FOR_REVIEW
                && d.action() != PaymentRiskDecision.Action.REJECT_WITH_REVIEW) {
            return;
        }
        OperationalInsightRepository repo = insights.getIfAvailable();
        if (repo == null) return;
        try {
            OperationalInsight insight = new OperationalInsight();
            insight.setCommuterId(commuterId);
            insight.setDriverId(driverId);
            insight.setType(OperationalInsight.Type.ALERT);
            insight.setCode(InsightCode.PAYMENT_RISK_HOLD);
            insight.setSeverity(OperationalInsight.Severity.WARN);
            insight.setMessage(summary + " level=" + d.level() + " action=" + d.action());
            insight.setCorrelationId(d.metadata().correlationId());
            insight.setCreatedAt(Instant.now());
            repo.save(insight);
        } catch (RuntimeException persistFail) {
            log.warn("agent.payment.insight.persist_failed error={}", persistFail.getMessage());
        }
    }
}
