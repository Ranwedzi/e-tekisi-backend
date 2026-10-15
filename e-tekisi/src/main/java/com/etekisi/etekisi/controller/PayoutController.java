package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.dto.api.ApiRecords.MarkPaidRequest;
import com.etekisi.etekisi.dto.api.ApiRecords.PayoutDto;
import com.etekisi.etekisi.dto.api.ApiRecords.PayoutOpsDto;
import com.etekisi.etekisi.dto.api.ApiRecords.PayoutRequestBody;
import com.etekisi.etekisi.dto.api.ApiRecords.RejectPayoutRequest;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.PayoutService;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
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

    // ------------------------------------------------- operators / admins --

    /** Payout queue for operators. {@code ?status=PENDING|APPROVED|PAID|REJECTED}; omit for all. */
    @GetMapping
    public List<PayoutOpsDto> listForOps(@RequestParam(required = false) String status) {
        requireStaff();
        return payouts.listForOps(status);
    }

    @PutMapping("/{payoutId}/approve")
    public PayoutOpsDto approve(@PathVariable Long payoutId) {
        return payouts.approve(payoutId, requireStaff());
    }

    @PutMapping("/{payoutId}/paid")
    public PayoutOpsDto markPaid(@PathVariable Long payoutId, @Valid @RequestBody MarkPaidRequest body) {
        return payouts.markPaid(payoutId, requireStaff(), body.reference());
    }

    @PutMapping("/{payoutId}/reject")
    public PayoutOpsDto reject(@PathVariable Long payoutId, @Valid @RequestBody RejectPayoutRequest body) {
        return payouts.reject(payoutId, requireStaff(), body.reason());
    }

    private Long requireStaff() {
        if (!currentUser.isStaff()) {
            throw new AccessDeniedException("Operators only");
        }
        return currentUser.id();
    }
}
