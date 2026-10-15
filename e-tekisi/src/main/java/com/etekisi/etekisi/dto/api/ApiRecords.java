package com.etekisi.etekisi.dto.api;

import com.etekisi.etekisi.model.DashboardNotification;
import com.etekisi.etekisi.model.Message;
import com.etekisi.etekisi.model.PayoutRequest;
import com.etekisi.etekisi.model.Wallet;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Small request / response records used by the controllers. Grouped here so
 * the API contract is readable in one place.
 */
public final class ApiRecords {

    private ApiRecords() {}

    // ------------------------------------------------------------- bookings --

    public record CreateBookingResponse(String status, Long bookingId, BookingDto booking, String message) {}

    public record TripCompletedResponse(String status, BookingDto booking, BigDecimal earned) {}

    public record EarningsResponse(BigDecimal totalEarnings, int tripCount,
                                   BigDecimal availableBalance, List<BookingDto> history) {}

    // --------------------------------------------------------------- wallet --

    public record WalletDto(BigDecimal balance, int totalTrips) {
        public static WalletDto from(Wallet w) {
            return new WalletDto(w.getBalance(), w.getTotalTrips() == null ? 0 : w.getTotalTrips());
        }
    }

    // -------------------------------------------------------------- payouts --

    public record PayoutRequestBody(
            BigDecimal amount,
            String method,
            String bankName,
            String accountNumber,
            String accountHolder,
            String phoneNumber) {}

    /** Payout as shown back to the driver; bank account and phone are masked. */
    public record PayoutDto(Long id, BigDecimal amount, String method, String bankName,
                            String accountNumberMasked, String accountHolder,
                            String phoneNumberMasked, String status, String createdAt,
                            String processedAt, String rejectionReason) {
        public static PayoutDto from(PayoutRequest p) {
            return new PayoutDto(p.getId(), p.getAmount(), p.getMethod(), p.getBankName(),
                    mask(p.getAccountNumber()), p.getAccountHolder(), mask(p.getPhoneNumber()),
                    p.getStatus(), str(p.getCreatedAt()), str(p.getProcessedAt()), p.getRejectionReason());
        }

        static String mask(String s) {
            if (s == null || s.isBlank()) return null;
            String digits = s.replaceAll("\\s", "");
            if (digits.length() <= 4) return "****";
            return "****" + digits.substring(digits.length() - 4);
        }
    }

    /**
     * Payout as shown to operators, who need the full details to pay it.
     * Only returned by staff-only endpoints.
     */
    public record PayoutOpsDto(Long id, Long driverId, String driverName, String driverPhone,
                               BigDecimal amount, String method, String bankName, String accountNumber,
                               String accountHolder, String phoneNumber, String status, String createdAt,
                               String processedAt, Long processedBy, String reference, String rejectionReason) {
        public static PayoutOpsDto from(PayoutRequest p, com.etekisi.etekisi.model.Driver d) {
            return new PayoutOpsDto(p.getId(), p.getDriverId(),
                    d != null ? d.getFullName() : null, d != null ? d.getPhoneNumber() : null,
                    p.getAmount(), p.getMethod(), p.getBankName(), p.getAccountNumber(), p.getAccountHolder(),
                    p.getPhoneNumber(), p.getStatus(), str(p.getCreatedAt()), str(p.getProcessedAt()),
                    p.getProcessedBy(), p.getReference(), p.getRejectionReason());
        }
    }

    public record MarkPaidRequest(@NotBlank @Size(max = 100) String reference) {}

    public record RejectPayoutRequest(@NotBlank @Size(max = 300) String reason) {}

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    // ------------------------------------------------------------- messages --

    public record SendMessageRequest(Long bookingId, @NotBlank @Size(max = 1000) String content) {}

    public record MessageDto(Long id, Long bookingId, Long senderId, String senderRole,
                             String content, String timestamp) {
        public static MessageDto from(Message m) {
            return new MessageDto(m.getId(), m.getBookingId(), m.getSenderId(),
                    m.getSenderRole() != null ? m.getSenderRole().name() : null,
                    m.getContent(), m.getTimestamp() != null ? m.getTimestamp().toString() : null);
        }
    }

    // -------------------------------------------------------- notifications --

    public record NotificationDto(Long id, String message, boolean read, String createdAt) {
        public static NotificationDto from(DashboardNotification n) {
            return new NotificationDto(n.getId(), n.getMessage(), n.isRead(),
                    n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);
        }
    }

    public record CountResponse(long count) {}

    // ------------------------------------------------------ queue / location --

    public record JoinQueueRequest(@NotBlank @Size(max = 200) String taxiRank) {}

    /** Coordinates are optional: a ping without them is stored but not fed to the agents. */
    public record LocationUpdateRequest(
            @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double lng) {}

    // ----------------------------------------------------------- saved ranks --

    public record SaveRankRequest(@NotBlank @Size(max = 120) String name,
                                  @NotBlank @Size(max = 300) String address) {}
}
