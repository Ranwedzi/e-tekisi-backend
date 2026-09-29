package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    // This allows the Controller to find a specific person's wallet
    Optional<Wallet> findByUserIdAndRole(Long userId, String role);
}