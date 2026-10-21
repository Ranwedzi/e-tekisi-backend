package com.etekisi.etekisi.service;

import com.etekisi.etekisi.dto.api.ApiRecords.MessageDto;
import com.etekisi.etekisi.dto.api.ApiRecords.NotificationDto;
import com.etekisi.etekisi.dto.api.ApiRecords.WalletDto;
import com.etekisi.etekisi.dto.api.BookingDto;
import com.etekisi.etekisi.dto.api.CommuterDto;
import com.etekisi.etekisi.dto.api.DriverDto;
import com.etekisi.etekisi.push.PushService;
import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.*;
import com.etekisi.etekisi.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * POPIA data-subject rights for commuters and drivers.
 *
 * <ul>
 *   <li><b>Access</b>: {@link #export} gathers everything held about the
 *       caller into one document.</li>
 *   <li><b>Deletion</b>: {@link #delete} removes or anonymises personal data.
 *       Trips and ledger rows stay (they are financial records the business
 *       has to keep), but they now point at an anonymised account. Chat they
 *       wrote is redacted, notifications, saved places, queue tickets and the
 *       last-known location are deleted, and the account can't sign in.</li>
 * </ul>
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final Set<BookingStatus> OPEN = EnumSet.of(BookingStatus.OFFERED, BookingStatus.ACCEPTED);

    private final UserRepository users;
    private final DriverRepository drivers;
    private final BookingRepository bookings;
    private final WalletRepository wallets;
    private final WalletTransactionRepository ledger;
    private final PayoutRequestRepository payouts;
    private final MessageRepository messages;
    private final NotificationRepository notifications;
    private final SavedRankRepository savedRanks;
    private final QueueRepository queue;
    private final DriverLocationRepository locations;
    private final BCryptPasswordEncoder passwords;
    private final DriverDocumentService driverDocuments;
    private final PushService push;

    public AccountService(UserRepository users, DriverRepository drivers, BookingRepository bookings,
                          WalletRepository wallets, WalletTransactionRepository ledger,
                          PayoutRequestRepository payouts, MessageRepository messages,
                          NotificationRepository notifications, SavedRankRepository savedRanks,
                          QueueRepository queue, DriverLocationRepository locations,
                          BCryptPasswordEncoder passwords, DriverDocumentService driverDocuments,
                          PushService push) {
        this.users = users;
        this.drivers = drivers;
        this.bookings = bookings;
        this.wallets = wallets;
        this.ledger = ledger;
        this.payouts = payouts;
        this.messages = messages;
        this.notifications = notifications;
        this.savedRanks = savedRanks;
        this.queue = queue;
        this.locations = locations;
        this.passwords = passwords;
        this.driverDocuments = driverDocuments;
        this.push = push;
    }

    // ------------------------------------------------------------------ export --

    @Transactional(readOnly = true)
    public Map<String, Object> export(Long id, Role role) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", Instant.now().toString());
        out.put("role", role.name());
        out.put("about", "Everything e-Tekisi holds about your account. Trips and payments are kept for "
                + "financial record-keeping even after you delete your account; they are then no longer linked to you.");

        if (role == Role.COMMUTER) {
            User u = users.findById(id).orElseThrow(() -> ApiException.notFound("Account"));
            out.put("profile", CommuterDto.from(u));
            out.put("wallet", wallets.findByUserIdAndRole(id, Role.COMMUTER).map(WalletDto::from).orElse(null));
            out.put("trips", bookings.findByCommuterIdOrderByIdDesc(id).stream().map(BookingDto::from).toList());
            out.put("savedPlaces", savedRanks.findByCommuterIdOrderByCreatedAtDesc(id).stream()
                    .map(r -> Map.of("name", r.getName(), "address", r.getAddress(),
                            "createdAt", String.valueOf(r.getCreatedAt()))).toList());
        } else if (role == Role.DRIVER) {
            Driver d = drivers.findById(id).orElseThrow(() -> ApiException.notFound("Account"));
            out.put("profile", DriverDto.from(d));
            out.put("trips", bookings.findByDriverIdOrderByIdDesc(id).stream().map(BookingDto::from).toList());
            // The driver's own bank details, in full: this is their data.
            out.put("payouts", payouts.findByDriverIdOrderByCreatedAtDesc(id).stream().map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId());
                m.put("amount", p.getAmount());
                m.put("method", p.getMethod());
                m.put("bankName", p.getBankName());
                m.put("accountNumber", p.getAccountNumber());
                m.put("accountHolder", p.getAccountHolder());
                m.put("phoneNumber", p.getPhoneNumber());
                m.put("status", p.getStatus());
                m.put("createdAt", String.valueOf(p.getCreatedAt()));
                return m;
            }).toList());
            out.put("lastKnownLocation", locations.findById(id).map(l -> Map.of(
                    "lat", l.getLat(), "lng", l.getLng(), "at", String.valueOf(l.getLastUpdated()))).orElse(null));
            // Metadata only; each file can be opened from the documents page.
            out.put("documents", driverDocuments.list(id).stream()
                    .map(com.etekisi.etekisi.dto.api.DocumentDto::from).toList());
            out.put("queueTickets", queue.findByDriverIdOrderByJoinedAtDesc(id).stream()
                    .map(t -> Map.of("rank", t.getTaxiRank(), "status", t.getStatus(),
                            "joinedAt", String.valueOf(t.getJoinedAt()))).toList());
        } else {
            throw ApiException.forbidden("NOT_SUPPORTED", "Staff accounts are exported by an administrator.");
        }

        out.put("moneyMovements", ledger.findByOwnerIdAndOwnerRoleOrderByIdDesc(id, role).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", t.getType().name());
            m.put("amount", t.getAmount());
            m.put("balanceAfter", t.getBalanceAfter());
            m.put("bookingId", t.getBookingId());
            m.put("at", String.valueOf(t.getCreatedAt()));
            return m;
        }).toList());
        out.put("messagesSent", messages.findBySenderIdAndSenderRoleOrderByTimestampAsc(id, role).stream()
                .map(MessageDto::from).toList());
        out.put("notifications", notifications.findByUserIdAndRoleOrderByCreatedAtDesc(id, role).stream()
                .map(NotificationDto::from).toList());
        return out;
    }

    // ------------------------------------------------------------------ delete --

    /**
     * Deletes the caller's account after re-checking their password. Refused
     * while a trip is open or money is still owed to or held for them, because
     * those need the account to settle.
     */
    @Transactional
    public void delete(Long id, Role role, String password) {
        if (role == Role.COMMUTER) {
            User u = users.findById(id).orElseThrow(() -> ApiException.notFound("Account"));
            requirePassword(password, u.getPassword());
            List<Booking> mine = bookings.findByCommuterIdOrderByIdDesc(id);
            if (mine.stream().anyMatch(b -> OPEN.contains(b.getStatus()))) {
                throw ApiException.conflict("OPEN_TRIP", "Finish or cancel your current trip before deleting your account.");
            }
            BigDecimal balance = wallets.findByUserIdAndRole(id, Role.COMMUTER).map(Wallet::getBalance).orElse(BigDecimal.ZERO);
            if (balance.signum() > 0) {
                throw ApiException.conflict("WALLET_NOT_EMPTY",
                        "Your wallet still holds R" + Money.of(balance) + ". Contact support for a refund before deleting your account.",
                        Map.of("balance", balance));
            }
            mine.stream().filter(b -> b.getStatus() == BookingStatus.PENDING).forEach(b -> b.setStatus(BookingStatus.CANCELLED));
            savedRanks.deleteByCommuterId(id);

            u.setFullName("Deleted user");
            u.setEmail("deleted-commuter-" + id + "@deleted.invalid");
            u.setPhoneNumber("0000000000");
            u.setPassword(passwords.encode(UUID.randomUUID().toString()));
            u.setDeletedAt(Instant.now());
        } else if (role == Role.DRIVER) {
            Driver d = drivers.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Account"));
            requirePassword(password, d.getPassword());
            if (bookings.findByDriverIdOrderByIdDesc(id).stream().anyMatch(b -> OPEN.contains(b.getStatus()))) {
                throw ApiException.conflict("OPEN_TRIP", "Finish or withdraw your current trips and offers before deleting your account.");
            }
            if (d.getBalance().signum() > 0) {
                throw ApiException.conflict("BALANCE_NOT_EMPTY",
                        "You still have R" + Money.of(d.getBalance()) + " to cash out. Request a payout before deleting your account.",
                        Map.of("balance", d.getBalance()));
            }
            boolean payoutInFlight = payouts.findByDriverIdOrderByCreatedAtDesc(id).stream()
                    .anyMatch(p -> PayoutRequest.PENDING.equals(p.getStatus()) || PayoutRequest.APPROVED.equals(p.getStatus()));
            if (payoutInFlight) {
                throw ApiException.conflict("PAYOUT_IN_PROGRESS", "Wait until your payout has been paid before deleting your account.");
            }
            queue.deleteByDriverId(id);
            locations.deleteById(id);
            driverDocuments.deleteAllFor(id);

            d.setFullName("Deleted driver");
            d.setEmail("deleted-driver-" + id + "@deleted.invalid");
            d.setPhoneNumber("0000000000");
            d.setTaxiPlate("DELETED");
            d.setLicenseNumber("deleted");
            d.setPassword(passwords.encode(UUID.randomUUID().toString()));
            d.setActive(false);
            d.setApprovalStatus(DriverApproval.REJECTED);
            d.setApprovalNote("Account deleted");
            d.setDeletedAt(Instant.now());
        } else {
            throw ApiException.forbidden("NOT_SUPPORTED", "Staff accounts are removed by an administrator.");
        }

        messages.redactBySender(id, role);
        notifications.deleteByUserIdAndRole(id, role);
        push.forgetAll(role, id);
        log.info("account.deleted role={} id={}", role, id);
    }

    private void requirePassword(String raw, String hash) {
        if (raw == null || raw.isBlank() || !passwords.matches(raw, hash)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PASSWORD_INCORRECT", "That password isn't right.", null);
        }
    }
}
