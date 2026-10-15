package com.etekisi.etekisi.service;

import com.etekisi.etekisi.dto.api.ApiRecords.PayoutDto;
import com.etekisi.etekisi.dto.api.ApiRecords.PayoutOpsDto;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Driver cash-outs. The balance is reserved (debited) when the request is
 * made; operators then approve it, mark it paid with the bank/provider
 * reference, or reject it, which gives the money back to the driver.
 */
@Service
public class PayoutService {

    private static final Set<String> METHODS = Set.of("BANK_TRANSFER", "FNB_EWALLET", "CAPITEC_CASH_SEND");

    private final DriverRepository drivers;
    private final PayoutRequestRepository payouts;
    private final LedgerService ledger;
    private final PaymentRiskReviewer risk;
    private final NotificationService notifications;
    private final BigDecimal minAmount;
    private final BigDecimal maxAmount;

    public PayoutService(DriverRepository drivers,
                         PayoutRequestRepository payouts,
                         LedgerService ledger,
                         PaymentRiskReviewer risk,
                         NotificationService notifications,
                         @Value("${tekisi.payouts.min-amount:20.00}") BigDecimal minAmount,
                         @Value("${tekisi.payouts.max-amount:5000.00}") BigDecimal maxAmount) {
        this.drivers = drivers;
        this.payouts = payouts;
        this.ledger = ledger;
        this.risk = risk;
        this.notifications = notifications;
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

    // ------------------------------------------------------------ operators --

    /** Staff view. {@code status} null means every status, newest first; otherwise oldest first. */
    @Transactional(readOnly = true)
    public List<PayoutOpsDto> listForOps(String status) {
        List<PayoutRequest> list = status == null || status.isBlank()
                ? payouts.findTop200ByOrderByCreatedAtDesc()
                : payouts.findTop200ByStatusOrderByCreatedAtAsc(status.trim().toUpperCase());
        Map<Long, Driver> byId = drivers.findAllById(list.stream().map(PayoutRequest::getDriverId).distinct().toList())
                .stream().collect(Collectors.toMap(Driver::getId, Function.identity()));
        return list.stream().map(p -> PayoutOpsDto.from(p, byId.get(p.getDriverId()))).toList();
    }

    @Transactional
    public PayoutOpsDto approve(Long payoutId, Long staffId) {
        PayoutRequest p = lockPayout(payoutId);
        requireStatus(p, PayoutRequest.PENDING);
        p.setStatus(PayoutRequest.APPROVED);
        stamp(p, staffId);
        notifications.send(p.getDriverId(), Role.DRIVER, "Your payout of R" + p.getAmount() + " was approved and will be paid soon.");
        return opsView(p);
    }

    @Transactional
    public PayoutOpsDto markPaid(Long payoutId, Long staffId, String reference) {
        PayoutRequest p = lockPayout(payoutId);
        requireStatus(p, PayoutRequest.APPROVED);
        p.setStatus(PayoutRequest.PAID);
        p.setReference(reference.trim());
        stamp(p, staffId);
        notifications.send(p.getDriverId(), Role.DRIVER, "Your payout of R" + p.getAmount() + " has been paid (ref " + p.getReference() + ").");
        return opsView(p);
    }

    /** Rejects a PENDING or APPROVED payout and returns the amount to the driver's balance. */
    @Transactional
    public PayoutOpsDto reject(Long payoutId, Long staffId, String reason) {
        PayoutRequest p = lockPayout(payoutId);
        requireStatus(p, PayoutRequest.PENDING, PayoutRequest.APPROVED);
        Driver driver = drivers.findByIdForUpdate(p.getDriverId()).orElseThrow(() -> ApiException.notFound("Driver"));
        driver.setBalance(Money.of(driver.getBalance().add(p.getAmount())));
        ledger.record(driver.getId(), Role.DRIVER, WalletTransaction.Type.PAYOUT_REVERSAL,
                p.getAmount(), driver.getBalance(), null, p.getId(), null);
        p.setStatus(PayoutRequest.REJECTED);
        p.setRejectionReason(reason.trim());
        stamp(p, staffId);
        notifications.send(p.getDriverId(), Role.DRIVER, "Your payout of R" + p.getAmount()
                + " was declined: " + p.getRejectionReason() + ". The money is back in your balance.");
        return PayoutOpsDto.from(p, driver);
    }

    private PayoutRequest lockPayout(Long id) {
        return payouts.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Payout"));
    }

    private static void requireStatus(PayoutRequest p, String... allowed) {
        if (!Set.of(allowed).contains(p.getStatus())) {
            throw ApiException.conflict("PAYOUT_WRONG_STATUS",
                    "This payout is " + p.getStatus() + " and can't be changed that way.");
        }
    }

    private static void stamp(PayoutRequest p, Long staffId) {
        p.setProcessedAt(Instant.now());
        p.setProcessedBy(staffId);
    }

    private PayoutOpsDto opsView(PayoutRequest p) {
        return PayoutOpsDto.from(p, drivers.findById(p.getDriverId()).orElse(null));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
