package com.etekisi.etekisi.service;

import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.WalletTransaction;
import com.etekisi.etekisi.repository.WalletTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Writes the append-only {@link WalletTransaction} ledger. Always called
 * inside the transaction that changed the balance, so the ledger row and the
 * balance change commit or roll back together.
 */
@Service
public class LedgerService {

    private final WalletTransactionRepository repo;

    public LedgerService(WalletTransactionRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long ownerId, Role ownerRole, WalletTransaction.Type type,
                       BigDecimal signedAmount, BigDecimal balanceAfter,
                       Long bookingId, Long payoutRequestId, String reference) {
        WalletTransaction tx = new WalletTransaction();
        tx.setOwnerId(ownerId);
        tx.setOwnerRole(ownerRole);
        tx.setType(type);
        tx.setAmount(Money.of(signedAmount));
        tx.setBalanceAfter(Money.of(balanceAfter));
        tx.setBookingId(bookingId);
        tx.setPayoutRequestId(payoutRequestId);
        tx.setReference(reference);
        tx.setCreatedAt(Instant.now());
        repo.save(tx);
    }

    /** How many {@code type} entries this owner has in the last 24 hours (for velocity checks). */
    @Transactional(readOnly = true)
    public int countLast24h(Long ownerId, Role ownerRole, WalletTransaction.Type type) {
        long n = repo.countByOwnerIdAndOwnerRoleAndTypeAndCreatedAtAfter(
                ownerId, ownerRole, type, Instant.now().minus(Duration.ofHours(24)));
        return (int) Math.min(n, Integer.MAX_VALUE);
    }
}
