package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.agent.dto.PaymentRiskDecision;
import com.etekisi.etekisi.agent.insight.InsightCode;
import com.etekisi.etekisi.agent.insight.OperationalInsight;
import com.etekisi.etekisi.agent.insight.OperationalInsightRepository;
import com.etekisi.etekisi.agent.payment.PaymentRiskAgent;
import com.etekisi.etekisi.model.Wallet;
import com.etekisi.etekisi.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    @Autowired
    private WalletRepository walletRepository;

    // Phase 3.6 — advisory only. The agent never blocks; HOLD_FOR_REVIEW
    // results in an OperationalInsight row that operators can act on.
    @Autowired(required = false)
    private PaymentRiskAgent riskAgent;

    @Autowired(required = false)
    private OperationalInsightRepository insights;

    @GetMapping("/{userId}")
    public Wallet getWallet(@PathVariable Long userId, @RequestParam String role) {
        return walletRepository.findByUserIdAndRole(userId, role)
                .orElseGet(() -> {
                    Wallet newWallet = new Wallet();
                    newWallet.setUserId(userId);
                    newWallet.setRole(role);
                    newWallet.setBalance(0.0);
                    newWallet.setTotalTrips(0);
                    return walletRepository.save(newWallet);
                });
    }

    @PostMapping("/{userId}/topup")
    public Wallet topUp(@PathVariable Long userId, @RequestParam String role, @RequestParam Double amount) {
        Wallet wallet = walletRepository.findByUserIdAndRole(userId, role)
                .orElseThrow(() -> new RuntimeException("Wallet not found"));

        // Risk evaluation runs BEFORE the balance mutation so an insight is
        // recorded even if persistence fails. We do not block — the agent
        // is advisory in Phase 3.6; ops triages from the insights feed.
        if (riskAgent != null) {
            try {
                int trips = wallet.getTotalTrips() == null ? 0 : wallet.getTotalTrips();
                PaymentRiskDecision d = riskAgent.evaluateTopUp(
                        userId, amount == null ? 0.0 : amount, trips, /*recent24hCount=*/0,
                        UUID.randomUUID().toString());
                recordHoldIfNeeded(d, userId, "topup");
            } catch (RuntimeException ex) {
                log.warn("agent.payment.topup.eval_failed userId={} error={}", userId, ex.getMessage());
            }
        }

        wallet.setBalance(wallet.getBalance() + amount);
        return walletRepository.save(wallet);
    }

    private void recordHoldIfNeeded(PaymentRiskDecision d, Long subjectId, String tag) {
        if (insights == null) return;
        if (d.action() != PaymentRiskDecision.Action.HOLD_FOR_REVIEW
                && d.action() != PaymentRiskDecision.Action.REJECT_WITH_REVIEW) {
            return;
        }
        try {
            OperationalInsight insight = new OperationalInsight();
            insight.setBookingId(null);
            insight.setCommuterId(d.subjectType() == PaymentRiskDecision.SubjectType.BOOKING ? subjectId : null);
            insight.setDriverId(d.subjectType() == PaymentRiskDecision.SubjectType.PAYOUT_REQUEST ? subjectId : null);
            insight.setType(OperationalInsight.Type.ALERT);
            insight.setCode(InsightCode.PAYMENT_RISK_HOLD);
            insight.setSeverity(OperationalInsight.Severity.WARN);
            insight.setMessage("Payment risk flag tag=" + tag
                    + " subjectId=" + subjectId
                    + " level=" + d.level()
                    + " action=" + d.action());
            insight.setCorrelationId(d.metadata().correlationId());
            insight.setCreatedAt(Instant.now());
            insights.save(insight);
        } catch (RuntimeException persistFail) {
            log.warn("agent.payment.insight.persist_failed subjectId={} error={}",
                    subjectId, persistFail.getMessage());
        }
    }
}