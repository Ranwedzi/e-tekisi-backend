package com.etekisi.etekisi.service;

import com.etekisi.etekisi.dto.api.ApiRecords.WalletDto;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.Wallet;
import com.etekisi.etekisi.model.WalletTransaction;
import com.etekisi.etekisi.payment.PaymentProvider;
import com.etekisi.etekisi.repository.WalletRepository;
import com.etekisi.etekisi.web.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/** Commuter wallets. Every balance change goes through here and writes a ledger row. */
@Service
public class WalletService {

    private final WalletRepository wallets;
    private final LedgerService ledger;
    private final ObjectProvider<PaymentProvider> payments;
    private final PaymentRiskReviewer risk;
    private final MoneyLimits limits;
    private final BigDecimal topUpMin;
    private final BigDecimal topUpMax;

    public WalletService(WalletRepository wallets,
                         LedgerService ledger,
                         ObjectProvider<PaymentProvider> payments,
                         PaymentRiskReviewer risk,
                         MoneyLimits limits,
                         @Value("${tekisi.payments.topup-min:10.00}") BigDecimal topUpMin,
                         @Value("${tekisi.payments.topup-max:2000.00}") BigDecimal topUpMax) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.payments = payments;
        this.risk = risk;
        this.limits = limits;
        this.topUpMin = topUpMin;
        this.topUpMax = topUpMax;
    }

    @Transactional
    public WalletDto view(Long commuterId) {
        return WalletDto.from(getOrCreate(commuterId));
    }

    @Transactional
    public Wallet getOrCreate(Long commuterId) {
        return wallets.findByUserIdAndRole(commuterId, Role.COMMUTER)
                .orElseGet(() -> wallets.saveAndFlush(newWallet(commuterId)));
    }

    /** Locks the commuter's wallet row for the rest of the caller's transaction, creating it if needed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Wallet lockOrCreate(Long commuterId) {
        return wallets.findByUserIdAndRoleForUpdate(commuterId, Role.COMMUTER)
                .orElseGet(() -> {
                    wallets.saveAndFlush(newWallet(commuterId));
                    return wallets.findByUserIdAndRoleForUpdate(commuterId, Role.COMMUTER).orElseThrow();
                });
    }

    /** Caller must hold the wallet lock (see {@link #lockOrCreate}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void debit(Wallet w, BigDecimal amount, WalletTransaction.Type type, Long bookingId) {
        BigDecimal after = w.getBalance().subtract(amount);
        if (after.signum() < 0) {
            // Callers check first; this is the last line of defence against overdrafts.
            throw ApiException.conflict("INSUFFICIENT_FUNDS", "Wallet balance is too low.");
        }
        w.setBalance(Money.of(after));
        ledger.record(w.getUserId(), Role.COMMUTER, type, amount.negate(), w.getBalance(), bookingId, null, null);
    }

    /** Caller must hold the wallet lock (see {@link #lockOrCreate}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(Wallet w, BigDecimal amount, WalletTransaction.Type type, Long bookingId, String reference) {
        w.setBalance(Money.of(w.getBalance().add(amount)));
        ledger.record(w.getUserId(), Role.COMMUTER, type, amount, w.getBalance(), bookingId, null, reference);
    }

    @Transactional
    public WalletDto topUp(Long commuterId, BigDecimal requested) {
        BigDecimal amount = Money.requireAmount(requested, topUpMin, topUpMax, "amount");
        PaymentProvider provider = payments.getIfAvailable();
        if (provider == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TOPUP_DISABLED",
                    "Wallet top-up is not available: no payment provider is configured.", null);
        }

        Wallet w = lockOrCreate(commuterId);
        limits.checkTopUp(w, amount); // before anything is charged
        int recent = ledger.countLast24h(commuterId, Role.COMMUTER, WalletTransaction.Type.TOPUP);
        risk.reviewTopUp(commuterId, amount.doubleValue(), w.getTotalTrips(), recent);

        String reference = "TOPUP-" + UUID.randomUUID();
        PaymentProvider.PaymentResult result = provider.charge(commuterId, amount, reference);
        if (!result.success()) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED",
                    "The payment was not completed: " + result.failureReason(), null);
        }
        credit(w, amount, WalletTransaction.Type.TOPUP, null, result.providerReference());
        return WalletDto.from(w);
    }

    @Transactional
    public MoneyLimits.Remaining limits(Long commuterId) {
        return limits.topUpRemaining(getOrCreate(commuterId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementTrips(Long commuterId) {
        Wallet w = lockOrCreate(commuterId);
        w.setTotalTrips(w.getTotalTrips() + 1);
    }

    private static Wallet newWallet(Long commuterId) {
        Wallet w = new Wallet();
        w.setUserId(commuterId);
        w.setRole(Role.COMMUTER);
        w.setBalance(Money.of(BigDecimal.ZERO));
        w.setTotalTrips(0);
        return w;
    }
}
