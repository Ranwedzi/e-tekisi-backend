package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    long countByOwnerIdAndOwnerRoleAndTypeAndCreatedAtAfter(Long ownerId, Role ownerRole,
                                                             WalletTransaction.Type type, Instant since);

    List<WalletTransaction> findByOwnerIdAndOwnerRoleOrderByIdDesc(Long ownerId, Role ownerRole);

    List<WalletTransaction> findByBookingId(Long bookingId);
}
