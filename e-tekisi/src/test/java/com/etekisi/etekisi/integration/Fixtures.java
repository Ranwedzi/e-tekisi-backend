package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.BookingRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.UserRepository;
import com.etekisi.etekisi.repository.WalletRepository;
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

    public Fixtures(UserRepository users, DriverRepository drivers,
                    WalletRepository wallets, BookingRepository bookings) {
        this.users = users;
        this.drivers = drivers;
        this.wallets = wallets;
        this.bookings = bookings;
    }

    public User commuter() {
        User u = new User();
        u.setFullName("Test Commuter");
        u.setEmail("c" + SEQ.incrementAndGet() + "@test.dev");
        u.setPhoneNumber("0710000000");
        u.setPassword("x");
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
        d.setActive(true); // online, so they can send offers
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
