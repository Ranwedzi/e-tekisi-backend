package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.agent.dto.PaymentRiskDecision;
import com.etekisi.etekisi.agent.insight.InsightCode;
import com.etekisi.etekisi.agent.insight.OperationalInsight;
import com.etekisi.etekisi.agent.insight.OperationalInsightRepository;
import com.etekisi.etekisi.agent.payment.PaymentRiskAgent;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.PayoutRequest;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.PayoutRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payouts")
public class PayoutController {

    private static final Logger log = LoggerFactory.getLogger(PayoutController.class);

    @Autowired private DriverRepository driverRepository;
    @Autowired private PayoutRequestRepository payoutRequestRepository;

    // Phase 3.6 — advisory payment-risk agent. Required=false so any test that
    // doesn't load the agent context still wires this controller cleanly.
    @Autowired(required = false) private PaymentRiskAgent riskAgent;
    @Autowired(required = false) private OperationalInsightRepository insights;

    @GetMapping("/driver/{driverId}")
    public List<PayoutRequest> getPayouts(@PathVariable Long driverId) {
        return payoutRequestRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
    }

    @PostMapping("/driver/{driverId}/request")
    public Map<String, Object> requestPayout(@PathVariable Long driverId, @RequestBody Map<String, String> payload) {
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found"));

        double amount = 0.0;
        try {
            amount = Double.parseDouble(payload.getOrDefault("amount", "0"));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid amount");
        }
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than 0");
        }

        String method = payload.getOrDefault("method", "").trim();
        if (!method.equals("BANK_TRANSFER") && !method.equals("FNB_EWALLET") && !method.equals("CAPITEC_CASH_SEND")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payout method");
        }

        String bankName = payload.getOrDefault("bankName", "").trim();
        String accountNumber = payload.getOrDefault("accountNumber", "").trim();
        String accountHolder = payload.getOrDefault("accountHolder", "").trim();
        String phoneNumber = payload.getOrDefault("phoneNumber", "").trim();

        if (method.equals("BANK_TRANSFER")) {
            if (bankName.isEmpty() || accountNumber.isEmpty() || accountHolder.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bank details are required");
            }
        } else {
            if (phoneNumber.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone number is required");
            }
        }

        if (driver.getBalance() < amount) {
            return Map.of(
                    "status", "INSUFFICIENT_FUNDS",
                    "available", driver.getBalance()
            );
        }

        driver.setBalance(driver.getBalance() - amount);
        driverRepository.save(driver);

        PayoutRequest request = new PayoutRequest();
        request.setDriverId(driverId);
        request.setAmount(amount);
        request.setMethod(method);
        request.setBankName(bankName.isEmpty() ? null : bankName);
        request.setAccountNumber(accountNumber.isEmpty() ? null : accountNumber);
        request.setAccountHolder(accountHolder.isEmpty() ? null : accountHolder);
        request.setPhoneNumber(phoneNumber.isEmpty() ? null : phoneNumber);
        payoutRequestRepository.save(request);

        // Phase 3.6 — advisory risk evaluation. Persists a PAYMENT_RISK_HOLD
        // insight if the agent flags the payout for review. Never blocks.
        evaluateRisk(request, driver);

        return Map.of("status", "PENDING", "requestId", request.getId());
    }

    private void evaluateRisk(PayoutRequest request, Driver driver) {
        if (riskAgent == null || insights == null) return;
        try {
            int trips = driver.getTripsCompleted() == null ? 0 : driver.getTripsCompleted();
            PaymentRiskDecision d = riskAgent.evaluatePayout(
                    request.getId(),
                    request.getAmount(),
                    driver.getBalance(),
                    trips,
                    /*recent24hCount=*/0,
                    UUID.randomUUID().toString());
            if (d.action() == PaymentRiskDecision.Action.HOLD_FOR_REVIEW
                    || d.action() == PaymentRiskDecision.Action.REJECT_WITH_REVIEW) {
                OperationalInsight insight = new OperationalInsight();
                insight.setDriverId(driver.getId());
                insight.setType(OperationalInsight.Type.ALERT);
                insight.setCode(InsightCode.PAYMENT_RISK_HOLD);
                insight.setSeverity(OperationalInsight.Severity.WARN);
                insight.setMessage("Payout flagged tag=payout payoutRequestId=" + request.getId()
                        + " amount=" + request.getAmount()
                        + " level=" + d.level()
                        + " action=" + d.action());
                insight.setCorrelationId(d.metadata().correlationId());
                insight.setCreatedAt(Instant.now());
                insights.save(insight);
            }
        } catch (RuntimeException ex) {
            log.warn("agent.payment.payout.eval_failed driverId={} error={}",
                    driver.getId(), ex.getMessage());
        }
    }
}
