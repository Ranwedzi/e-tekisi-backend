package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.dto.api.ApiRecords.WalletDto;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.UserRepository;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.AccountSecurityService;
import com.etekisi.etekisi.service.WalletService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Commuter wallet. The old {@code ?role=} parameter is ignored: wallets
 * belong to commuters, and drivers see their balance on the driver profile.
 */
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService wallets;
    private final CurrentUser currentUser;
    private final UserRepository users;

    public WalletController(WalletService wallets, CurrentUser currentUser, UserRepository users) {
        this.wallets = wallets;
        this.currentUser = currentUser;
        this.users = users;
    }

    @GetMapping("/{userId}")
    public WalletDto getWallet(@PathVariable Long userId) {
        currentUser.requireSelf(userId, Role.COMMUTER);
        return wallets.view(userId);
    }

    /** How much can still be topped up today, and room left under the wallet's maximum balance. */
    @GetMapping("/{userId}/limits")
    public com.etekisi.etekisi.service.MoneyLimits.Remaining limits(@PathVariable Long userId) {
        currentUser.requireSelf(userId, Role.COMMUTER);
        return wallets.limits(userId);
    }

    /** Sandbox-only until a real PaymentProvider exists; see SandboxPaymentProvider. */
    @PostMapping("/{userId}/topup")
    public WalletDto topUp(@PathVariable Long userId, @RequestParam BigDecimal amount) {
        currentUser.requireSelf(userId, Role.COMMUTER);
        users.findById(userId).ifPresent(AccountSecurityService::requireVerified);
        return wallets.topUp(userId, amount);
    }
}
