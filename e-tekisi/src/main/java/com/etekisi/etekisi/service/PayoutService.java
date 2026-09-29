package com.etekisi.etekisi.service;

import com.etekisi.etekisi.dto.api.ApiRecords.PayoutDto;
import com.etekisi.etekisi.dto.api.ApiRecords.PayoutRequestBody;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.PayoutRequest;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.WalletTransaction;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.PayoutRequestRepository;
import com.etekisi.etekisi.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Driver cash-outs. The balance is reserved (debited) when the request is made. */
@Service
public class PayoutService {

    private static final Set<String> METHODS = Set.of("BANK_TRANSFER", "FNB_EWALLET", "CAPITEC_CASH_SEND");

    private final DriverRepository drivers;
    private final PayoutRequestRepository payouts;
    private final LedgerService ledger;
    private final PaymentRiskReviewer risk;
    private final BigDecimal minAmount;
    private final BigDecimal maxAmount;

    public PayoutService(DriverRepository drivers,
                         PayoutRequestRepository payouts,
                         LedgerService ledger,
                         PaymentRiskReviewer risk,
                         @Value("${tekisi.payouts.min-amount:20.00}") BigDecimal minAmount,
                         @Value("${tekisi.payouts.max-amount:5000.00}") BigDecimal maxAmount) {
        this.drivers = drivers;
        this.payouts = payouts;
        this.ledger = ledger;
        this.risk = risk;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
    }

    @Transactional(readOnly = true)
    public List<PayoutDto> list(Long driverId) {
        return payouts.findByDriverIdOrderByCreatedAtDesc(driverId).stream().map(PayoutDto::from).toList();
    }

    @Transactional
    public PayoutDto request(Long driverId, PayoutRequestBody body) {
        if (body == null) {
            throw ApiException.badRequest("INVALID_PAYOUT", "Request body is required");
        }
        BigDecimal amount = Money.requireAmount(body.amount(), minAmount, maxAmount, "amount");

        String method = trim(body.method());
        if (!METHODS.contains(method)) {
            throw ApiException.badRequest("INVALID_PAYOUT_METHOD", "Invalid payout method");
        }
        String bankName = trim(body.bankName());
        String accountNumber = trim(body.accountNumber());
        String accountHolder = trim(body.accountHolder());
        String phoneNumber = trim(body.phoneNumber());
        if (method.equals("BANK_TRANSFER")) {
            if (bankName.isEmpty() || accountNumber.isEmpty() || accountHolder.isEmpty()) {
                throw ApiException.badRequest("INVALID_PAYOUT", "Bank name, account number and account holder are required");
            }
            if (!accountNumber.matches("^[0-9 ]{6,20}$")) {
                throw ApiException.badRequest("INVALID_PAYOUT", "Account number must be 6-20 digits");
            }
        } else if (!phoneNumber.matches("^[+]?[0-9 ()-]{8,20}$")) {
            throw ApiException.badRequest("INVALID_PAYOUT", "A valid phone number is required");
        }

        Driver driver = drivers.findByIdForUpdate(driverId).orElseThrow(() -> ApiException.notFound("Driver"));
        BigDecimal balanceBefore = driver.getBalance();
        if (balanceBefore.compareTo(amount) < 0) {
            throw ApiException.conflict("INSUFFICIENT_FUNDS", "Your available balance is too low for this payout.",
                    Map.of("available", balanceBefore));
        }

        PayoutRequest request = new PayoutRequest();
        request.setDriverId(driverId);
        request.setAmount(amount);
        request.setMethod(method);
        request.setBankName(bankName.isEmpty() ? null : bankName);
        request.setAccountNumber(accountNumber.isEmpty() ? null : accountNumber);
        request.setAccountHolder(accountHolder.isEmpty() ? null : accountHolder);
        request.setPhoneNumber(phoneNumber.isEmpty() ? null : phoneNumber);
        request = payouts.save(request);

        driver.setBalance(Money.of(balanceBefore.subtract(amount)));
        ledger.record(driverId, Role.DRIVER, WalletTransaction.Type.PAYOUT,
                amount.negate(), driver.getBalance(), null, request.getId(), null);

        int recent = (int) payouts.countByDriverIdAndCreatedAtAfter(driverId, Instant.now().minus(Duration.ofHours(24))) - 1;
        risk.reviewPayout(driverId, request.getId(), amount.doubleValue(), balanceBefore.doubleValue(),
                driver.getTripsCompleted(), Math.max(recent, 0));
        return PayoutDto.from(request);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
