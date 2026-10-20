package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.DriverApproval;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.DriverDocumentService;
import com.etekisi.etekisi.service.NotificationService;
import com.etekisi.etekisi.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Operator review of driver accounts (OPERATOR/ADMIN only, via SecurityConfig).
 * Operators check the driver's uploaded documents (OpsDocumentController) and
 * can only approve once the required ones are accepted; until then the driver
 * can't take trips.
 */
@RestController
@RequestMapping("/api/ops/drivers")
public class OpsDriverController {

    /** Everything an operator needs to check a driver. Staff-only. */
    public record DriverReviewDto(Long id, String fullName, String email, String phoneNumber,
                                  String taxiPlate, String licenseNumber, String approvalStatus,
                                  String approvalNote, String registeredAt, String approvalDecidedAt,
                                  Long approvalDecidedBy, int tripsCompleted) {
        static DriverReviewDto from(Driver d) {
            return new DriverReviewDto(d.getId(), d.getFullName(), d.getEmail(), d.getPhoneNumber(),
                    d.getTaxiPlate(), d.getLicenseNumber(), d.getApprovalStatus().name(), d.getApprovalNote(),
                    str(d.getRegisteredAt()), str(d.getApprovalDecidedAt()), d.getApprovalDecidedBy(),
                    d.getTripsCompleted() == null ? 0 : d.getTripsCompleted());
        }

        private static String str(Instant i) {
            return i != null ? i.toString() : null;
        }
    }

    public record RejectDriverRequest(@NotBlank @Size(max = 300) String reason) {}

    private final DriverRepository drivers;
    private final NotificationService notifications;
    private final CurrentUser currentUser;
    private final DriverDocumentService documents;

    public OpsDriverController(DriverRepository drivers, NotificationService notifications, CurrentUser currentUser,
                               DriverDocumentService documents) {
        this.drivers = drivers;
        this.notifications = notifications;
        this.currentUser = currentUser;
        this.documents = documents;
    }

    /** {@code ?status=PENDING_APPROVAL|APPROVED|REJECTED} (default PENDING_APPROVAL), oldest sign-ups first. */
    @GetMapping
    public List<DriverReviewDto> list(@RequestParam(defaultValue = "PENDING_APPROVAL") DriverApproval status) {
        return drivers.findTop200ByApprovalStatusOrderByRegisteredAtAsc(status).stream()
                .map(DriverReviewDto::from).toList();
    }

    @PutMapping("/{driverId}/approve")
    @Transactional
    public DriverReviewDto approve(@PathVariable Long driverId) {
        Driver d = load(driverId);
        if (d.isApproved()) {
            throw ApiException.conflict("DRIVER_ALREADY_APPROVED", "This driver is already approved.");
        }
        documents.requireComplete(driverId);
        d.setApprovalStatus(DriverApproval.APPROVED);
        d.setApprovalNote(null);
        stamp(d);
        notifications.send(d.getId(), Role.DRIVER, "Your driver account has been approved. Go online to start taking trips.");
        return DriverReviewDto.from(d);
    }

    /** Also takes the driver offline. Works on pending and approved drivers (e.g. a permit lapsed). */
    @PutMapping("/{driverId}/reject")
    @Transactional
    public DriverReviewDto reject(@PathVariable Long driverId, @Valid @RequestBody RejectDriverRequest body) {
        Driver d = load(driverId);
        d.setApprovalStatus(DriverApproval.REJECTED);
        d.setApprovalNote(body.reason().trim());
        d.setActive(false);
        stamp(d);
        notifications.send(d.getId(), Role.DRIVER, "Your driver account was not approved: " + d.getApprovalNote());
        return DriverReviewDto.from(d);
    }

    private Driver load(Long id) {
        return drivers.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Driver"));
    }

    private void stamp(Driver d) {
        d.setApprovalDecidedAt(Instant.now());
        d.setApprovalDecidedBy(currentUser.id());
    }
}
