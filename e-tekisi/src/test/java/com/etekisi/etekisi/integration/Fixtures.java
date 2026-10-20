package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.BookingRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.UserRepository;
import com.etekisi.etekisi.repository.WalletRepository;
import com.etekisi.etekisi.notify.EmailSender;
import com.etekisi.etekisi.notify.NotifyConfig;
import com.etekisi.etekisi.notify.SmsSender;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/** Seeds rows directly through the repositories. Emails are unique per call, so tests can share one database. */
@Component
public class Fixtures {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private final UserRepository users;
    private final DriverRepository drivers;
    private final WalletRepository wallets;
    private final BookingRepository bookings;
    private final EmailSender email;
    private final SmsSender sms;

    public Fixtures(UserRepository users, DriverRepository drivers,
                    WalletRepository wallets, BookingRepository bookings,
                    EmailSender email, SmsSender sms) {
        this.users = users;
        this.drivers = drivers;
        this.wallets = wallets;
        this.bookings = bookings;
        this.email = email;
        this.sms = sms;
    }

    /** The token from the newest email to {@code to} whose link goes to {@code /page}. */
    public String emailToken(String to, String page) {
        return ((NotifyConfig.LogEmailSender) email).recent().stream()
                .filter(m -> m[0].equalsIgnoreCase(to) && m[2].contains("/" + page + "?token="))
                .findFirst()
                .map(m -> m[2].replaceAll("(?s).*/" + page + "\\?token=([A-Za-z0-9_-]+).*", "$1"))
                .orElseThrow(() -> new AssertionError("no " + page + " email to " + to));
    }

    /** The 6-digit code from the newest SMS to {@code e164}. */
    public String smsCode(String e164) {
        return ((NotifyConfig.LogSmsSender) sms).recent().stream()
                .filter(m -> m[0].equals(e164))
                .findFirst()
                .map(m -> m[1].replaceAll(".*\\b(\\d{6})\\b.*", "$1"))
                .orElseThrow(() -> new AssertionError("no SMS to " + e164));
    }

    public User commuter() {
        User u = new User();
        u.setFullName("Test Commuter");
        u.setEmail("c" + SEQ.incrementAndGet() + "@test.dev");
        u.setPhoneNumber("0710000000");
        u.setPassword("x");
        u.setEmailVerifiedAt(java.time.Instant.now());
        u.setTermsVersion("test-terms-v1");
        return users.save(u);
    }

    public User commuterWithBalance(String balance) {
        User u = commuter();
        Wallet w = new Wallet();
        w.setUserId(u.getId());
        w.setRole(Role.COMMUTER);
        w.setBalance(new BigDecimal(balance));
        w.setTotalTrips(0);
        wallets.save(w);
        return u;
    }

    public Driver driver() {
        Driver d = new Driver();
        d.setFullName("Test Driver");
        d.setEmail("d" + SEQ.incrementAndGet() + "@test.dev");
        d.setPhoneNumber("0820000000");
        d.setLicenseNumber("L1");
        d.setTaxiPlate("ABC123GP");
        d.setPassword("x");
        d.setApprovalStatus(DriverApproval.APPROVED); // vetted
        d.setActive(true); // online, so they can send offers
        d.setEmailVerifiedAt(java.time.Instant.now());
        d.setTermsVersion("test-terms-v1");
        return drivers.save(d);
    }

    public Booking pendingBooking(Long commuterId) {
        Booking b = new Booking();
        b.setCommuterId(commuterId);
        b.setPickupLocation("Park Station");
        b.setDestination("Bara");
        b.setStatus(BookingStatus.PENDING);
        return bookings.save(b);
    }

    public BigDecimal walletBalance(Long commuterId) {
        return wallets.findByUserIdAndRole(commuterId, Role.COMMUTER).orElseThrow().getBalance();
    }

    public BigDecimal driverBalance(Long driverId) {
        return drivers.findById(driverId).orElseThrow().getBalance();
    }

    public BookingStatus status(Long bookingId) {
        return bookings.findById(bookingId).orElseThrow().getStatus();
    }
}
