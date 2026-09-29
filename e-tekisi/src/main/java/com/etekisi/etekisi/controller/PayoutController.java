package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.dto.api.ApiRecords.PayoutDto;
import com.etekisi.etekisi.dto.api.ApiRecords.PayoutRequestBody;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.PayoutService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payouts")
public class PayoutController {

    private final PayoutService payouts;
    private final CurrentUser currentUser;

    public PayoutController(PayoutService payouts, CurrentUser currentUser) {
        this.payouts = payouts;
        this.currentUser = currentUser;
    }

    @GetMapping("/driver/{driverId}")
    public List<PayoutDto> getPayouts(@PathVariable Long driverId) {
        currentUser.requireSelf(driverId, Role.DRIVER);
        return payouts.list(driverId);
    }

    @PostMapping("/driver/{driverId}/request")
    public PayoutDto requestPayout(@PathVariable Long driverId, @RequestBody PayoutRequestBody body) {
        // Only the driver themselves may move their money; staff can view but not request.
        Long caller = currentUser.requireRole(Role.DRIVER);
        currentUser.requireSelf(driverId, Role.DRIVER);
        return payouts.request(caller, body);
    }
}
