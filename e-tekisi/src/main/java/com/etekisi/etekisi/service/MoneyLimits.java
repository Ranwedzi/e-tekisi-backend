package com.etekisi.etekisi.service;

import com.etekisi.etekisi.model.PayoutRequest;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.Wallet;
import com.etekisi.etekisi.model.WalletTransaction;
import com.etekisi.etekisi.repository.PayoutRequestRepository;
import com.etekisi.etekisi.repository.WalletTransactionRepository;
import com.etekisi.etekisi.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hard limits on money movements, checked before anything is charged or
 * debited. Unlike the advisory PaymentRiskAgent these always apply.
 *
 * <ul>
 *   <li>Top-ups: at most {@code topup.daily-count} a day and
 *       {@code topup.daily-rand} in rolling 24 hours, lower for new accounts
 *       (fewer than {@code new-account-trips} trips); and a wallet never holds
 *       more than {@code wallet.max-balance-rand}, which also caps how much
 *       customer money e-Tekisi holds per person.</li>
 *   <li>Payouts: at most {@code payout.daily-count} requests and
 *       {@code payout.daily-rand} (rejected ones excluded) in 24 hours.</li>
 * </ul>
 *
 * Refusals are 409 with code {@code LIMIT_REACHED}, the limit, what's left
 * and when it resets.
 */
@Service
public class MoneyLimits {

    public record Remaining(BigDecimal topUpToday, int topUpsLeftToday, BigDecimal walletRoom,
                            BigDecimal dailyTopUpLimit, BigDecimal maxWalletBalance) {}
    public record PayoutRemaining(BigDecimal payoutToday, int payoutsLeftToday, BigDecimal dailyPayoutLimit) {}

    private static final Duration DAY = Duration.ofHours(24);

    private final WalletTransactionRepository ledger;
    private final PayoutRequestRepository payouts;
    private final BigDecimal topUpDaily;
    private final BigDecimal topUpDailyNew;
    private final int topUpDailyCount;
    private final BigDecimal maxBalance;
    private final int newAccountTrips;
    private final BigDecimal payoutDaily;
    private final int payoutDailyCount;

    public MoneyLimits(WalletTransactionRepository ledger, PayoutRequestRepository payouts,
                       @Value("${tekisi.limits.topup.daily-rand:2000}") BigDecimal topUpDaily,
                       @Value("${tekisi.limits.topup.daily-rand-new-account:500}") BigDecimal topUpDailyNew,
                       @Value("${tekisi.limits.topup.daily-count:5}") int topUpDailyCount,
                       @Value("${tekisi.limits.wallet.max-balance-rand:3000}") BigDecimal maxBalance,
                       @Value("${tekisi.limits.new-account-trips:3}") int newAccountTrips,
                       @Value("${tekisi.limits.payout.daily-rand:5000}") BigDecimal payoutDaily,
                       @Value("${tekisi.limits.payout.daily-count:3}") int payoutDailyCount) {
        this.ledger = ledger;
        this.payouts = payouts;
        this.topUpDaily = topUpDaily;
        this.topUpDailyNew = topUpDailyNew;
        this.topUpDailyCount = topUpDailyCount;
        this.maxBalance = maxBalance;
        this.newAccountTrips = newAccountTrips;
        this.payoutDaily = payoutDaily;
        this.payoutDailyCount = payoutDailyCount;
    }

    public Remaining topUpRemaining(Wallet w) {
        Instant since = Instant.now().minus(DAY);
        List<WalletTransaction> recent = ledger.findByOwnerIdAndOwnerRoleAndTypeAndCreatedAtAfterOrderByCreatedAtAsc(
                w.getUserId(), Role.COMMUTER, WalletTransaction.Type.TOPUP, since);
        BigDecimal used = recent.stream().map(WalletTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cap = w.getTotalTrips() < newAccountTrips ? topUpDailyNew : topUpDaily;
        BigDecimal room = maxBalance.subtract(w.getBalance()).max(BigDecimal.ZERO);
        return new Remaining(Money.of(cap.subtract(used).max(BigDecimal.ZERO)),
                Math.max(0, topUpDailyCount - recent.size()), Money.of(room), Money.of(cap), Money.of(maxBalance));
    }

    /** Call with the wallet locked, before charging. */
    public void checkTopUp(Wallet w, BigDecimal amount) {
        Instant since = Instant.now().minus(DAY);
        List<WalletTransaction> recent = ledger.findByOwnerIdAndOwnerRoleAndTypeAndCreatedAtAfterOrderByCreatedAtAsc(
                w.getUserId(), Role.COMMUTER, WalletTransaction.Type.TOPUP, since);
        Instant resets = recent.isEmpty() ? null : recent.get(0).getCreatedAt().plus(DAY);
        if (recent.size() >= topUpDailyCount) {
            throw limit("You can top up at most " + topUpDailyCount + " times in 24 hours.", "TOPUP_DAILY_COUNT",
                    BigDecimal.valueOf(topUpDailyCount), BigDecimal.ZERO, resets);
        }
        BigDecimal used = recent.stream().map(WalletTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean isNew = w.getTotalTrips() < newAccountTrips;
        BigDecimal cap = isNew ? topUpDailyNew : topUpDaily;
        BigDecimal left = cap.subtract(used).max(BigDecimal.ZERO);
        if (amount.compareTo(left) > 0) {
            throw limit("You can top up R" + Money.of(left) + " more today (limit R" + Money.of(cap) + " per 24 hours"
                            + (isNew ? " for new accounts; it goes up after " + newAccountTrips + " trips" : "") + ").",
                    "TOPUP_DAILY_LIMIT", cap, left, resets);
        }
        BigDecimal room = maxBalance.subtract(w.getBalance()).max(BigDecimal.ZERO);
        if (amount.compareTo(room) > 0) {
            throw limit("A wallet can hold at most R" + Money.of(maxBalance) + "; you can add R" + Money.of(room) + ".",
                    "WALLET_BALANCE_LIMIT", maxBalance, room, null);
        }
    }

    public PayoutRemaining payoutRemaining(Long driverId) {
        List<PayoutRequest> recent = recentPayouts(driverId);
        BigDecimal used = counted(recent);
        return new PayoutRemaining(Money.of(payoutDaily.subtract(used).max(BigDecimal.ZERO)),
                Math.max(0, payoutDailyCount - recent.size()), Money.of(payoutDaily));
    }

    /** Call with the driver row locked, before debiting. */
    public void checkPayout(Long driverId, BigDecimal amount) {
        List<PayoutRequest> recent = recentPayouts(driverId);
        Instant resets = recent.isEmpty() ? null
                : recent.stream().map(PayoutRequest::getCreatedAt).min(Instant::compareTo).orElseThrow().plus(DAY);
        if (recent.size() >= payoutDailyCount) {
            throw limit("You can request at most " + payoutDailyCount + " payouts in 24 hours.", "PAYOUT_DAILY_COUNT",
                    BigDecimal.valueOf(payoutDailyCount), BigDecimal.ZERO, resets);
        }
        BigDecimal left = payoutDaily.subtract(counted(recent)).max(BigDecimal.ZERO);
        if (amount.compareTo(left) > 0) {
            throw limit("You can cash out R" + Money.of(left) + " more today (limit R" + Money.of(payoutDaily) + " per 24 hours).",
                    "PAYOUT_DAILY_LIMIT", payoutDaily, left, resets);
        }
    }

    private List<PayoutRequest> recentPayouts(Long driverId) {
        return payouts.findByDriverIdAndCreatedAtAfter(driverId, Instant.now().minus(DAY));
    }

    /** Rejected payouts gave the money back, so they don't use up the daily amount (they still count as a request). */
    private static BigDecimal counted(List<PayoutRequest> recent) {
        return recent.stream().filter(p -> !PayoutRequest.REJECTED.equals(p.getStatus()))
                .map(PayoutRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ApiException limit(String detail, String which, BigDecimal limit, BigDecimal remaining, Instant resets) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("limit", which);
        extras.put("limitValue", limit);
        extras.put("remaining", Money.of(remaining));
        if (resets != null) extras.put("resetsAt", resets.toString());
        return ApiException.conflict("LIMIT_REACHED", detail, extras);
    }
}
