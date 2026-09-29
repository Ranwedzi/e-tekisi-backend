package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserIdAndRole(Long userId, Role role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId and w.role = :role")
    Optional<Wallet> findByUserIdAndRoleForUpdate(@Param("userId") Long userId, @Param("role") Role role);
}
