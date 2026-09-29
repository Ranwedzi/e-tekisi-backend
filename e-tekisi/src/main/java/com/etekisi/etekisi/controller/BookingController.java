package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.agent.event.AgentEventPublisher;
import com.etekisi.etekisi.agent.idempotency.IdempotencyService;
import com.etekisi.etekisi.config.SloMetrics;
import com.etekisi.etekisi.dto.api.BookingDto;
import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired private BookingRepository bookingRepository;
    @Autowired private DriverRepository driverRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private BookingDeclineRepository bookingDeclineRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private AgentEventPublisher agentEvents;
    @Autowired private SloMetrics slo;
    @Autowired private IdempotencyService idempotency;

    /**
     * Create a new booking. Returns a JSON envelope including the new
     * {@code bookingId} so the frontend can chain to accept / complete /
     * cancel without a follow-up GET.
     *
     * <p>Honors {@code Idempotency-Key} header — repeated POSTs with the
     * same key return the original response without duplicating the row.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @Valid @RequestBody Booking booking,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return idempotency.execute(idemKey, "POST /api/bookings/create", () -> {
            booking.setStatus("PENDING");
            Booking saved = bookingRepository.save(booking);
            // Advisory: agent layer subscribes via BookingCreatedEvent. The agent
            // never mutates booking state — it only writes an audit row.
            agentEvents.publish(agentEvents.bookingCreated(
                    saved.getId(), saved.getCommuterId(),
                    saved.getPickupLocation(), saved.getDestination(),
                    null, null));
            slo.bookingCreated();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "PENDING");
            body.put("bookingId", saved.getId());
            body.put("booking", BookingDto.from(saved));
            body.put("message", "Booking saved. Waiting for a driver to set the fare.");
            return body;
        });
    }

    @GetMapping("/pending")
    public List<BookingDto> getPendingBookings() {
        return BookingDto.from(bookingRepository.findByStatus("PENDING"));
    }

    @GetMapping("/pending/{driverId}")
    public List<BookingDto> getPendingBookingsForDriver(@PathVariable Long driverId) {
        List<Booking> pending = bookingRepository.findByStatus("PENDING");
        List<BookingDecline> declined = bookingDeclineRepository.findByDriverId(driverId);
        Set<Long> declinedIds = new HashSet<>();
        declined.forEach(d -> declinedIds.add(d.getBookingId()));
        pending.removeIf(b -> declinedIds.contains(b.getId()));
        return BookingDto.from(pending);
    }

    @GetMapping("/user/{userId}")
    public List<BookingDto> getMyBookings(@PathVariable Long userId) {
        // Returns all bookings where this user is the commuter
        return BookingDto.from(bookingRepository.findByCommuterId(userId));
    }

    @GetMapping("/driver/{driverId}")
    public List<BookingDto> getDriverBookings(@PathVariable Long driverId) {
        return BookingDto.from(bookingRepository.findByDriverId(driverId));
    }

    @GetMapping("/driver/{driverId}/earnings")
    public Map<String, Object> getDriverEarnings(@PathVariable Long driverId) {
        Driver driver = driverRepository.findById(driverId).orElse(null);
        List<Booking> completed = bookingRepository.findByDriverIdAndStatus(driverId, "COMPLETED");
        double total = 0.0;
        for (Booking b : completed) {
            double fare = b.getFare() != null ? b.getFare() : 0.0;
            double tip = b.getTip() != null ? b.getTip() : 0.0;
            total += fare + tip;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("totalEarnings", total);
        payload.put("tripCount", completed.size());
        payload.put("history", BookingDto.from(completed));
        payload.put("availableBalance", driver != null ? driver.getBalance() : 0.0);
        return payload;
    }

    @PutMapping("/{bookingId}/accept/{driverId}")
    public Map<String, Object> acceptBooking(@PathVariable Long bookingId,
                                             @PathVariable Long driverId,
                                             @RequestParam Double fare,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return idempotency.execute(idemKey,
                "PUT /api/bookings/" + bookingId + "/accept/" + driverId,
                () -> doAcceptBooking(bookingId, driverId, fare));
    }

    private Map<String, Object> doAcceptBooking(Long bookingId, Long driverId, Double fare) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        Driver driver = driverRepository.findById(driverId).orElseThrow();
        User commuter = userRepository.findById(booking.getCommuterId()).orElseThrow();

        double totalFare = (fare != null ? fare : 0.0) + (booking.getTip() != null ? booking.getTip() : 0.0);
        Wallet wallet = walletRepository.findByUserIdAndRole(booking.getCommuterId(), "COMMUTER")
                .orElseGet(() -> {
                    Wallet newWallet = new Wallet();
                    newWallet.setUserId(booking.getCommuterId());
                    newWallet.setRole("COMMUTER");
                    newWallet.setBalance(0.0);
                    newWallet.setTotalTrips(0);
                    return walletRepository.save(newWallet);
                });

        if (wallet.getBalance() < totalFare) {
            booking.setStatus("CANCELLED");
            bookingRepository.save(booking);
            slo.bookingCancelled("INSUFFICIENT_FUNDS");

            DashboardNotification cn = new DashboardNotification();
            cn.setUserId(commuter.getId());
            cn.setRole("COMMUTER");
            cn.setMessage("Trip cancelled: insufficient wallet balance for fare R" + totalFare);
            notificationRepository.save(cn);

            DashboardNotification dn = new DashboardNotification();
            dn.setUserId(driverId);
            dn.setRole("DRIVER");
            dn.setMessage("Trip cancelled: commuter has insufficient wallet balance.");
            notificationRepository.save(dn);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "INSUFFICIENT_FUNDS");
            response.put("required", totalFare);
            response.put("balance", wallet.getBalance());
            response.put("cancelled", true);
            return response;
        }

        wallet.setBalance(wallet.getBalance() - totalFare);
        walletRepository.save(wallet);

        booking.setDriverId(driverId);
        booking.setFare(fare);
        booking.setStatus("ACCEPTED");
        bookingRepository.save(booking);

        // Trip just transitioned PENDING -> ACCEPTED. Agent listeners can now
        // start collecting GPS pings and running safety evaluations.
        agentEvents.publish(agentEvents.tripStarted(
                booking.getId(), driverId, booking.getCommuterId(), fare));

        // Notify Commuter
        DashboardNotification cn = new DashboardNotification();
        cn.setUserId(commuter.getId());
        cn.setRole("COMMUTER");
        cn.setMessage("Driver " + driver.getFullName() + " accepted your trip! Fare: R" + fare);
        notificationRepository.save(cn);

        // Notify Driver (Confirmation of scheduled commitment)
        DashboardNotification dn = new DashboardNotification();
        dn.setUserId(driverId);
        dn.setRole("DRIVER");
        dn.setMessage("Trip confirmed! You are scheduled for " + booking.getPickupLocation() + " to " + booking.getDestination());
        notificationRepository.save(dn);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ACCEPTED");
        response.put("fare", fare);
        response.put("driverName", driver.getFullName());
        response.put("driverPhone", driver.getPhoneNumber());
        response.put("taxiPlate", driver.getTaxiPlate());
        response.put("commuterName", commuter.getFullName());
        response.put("commuterPhone", commuter.getPhoneNumber());
        return response;
    }

    @PostMapping("/{bookingId}/decline/{driverId}")
    public Map<String, Object> declineBooking(@PathVariable Long bookingId, @PathVariable Long driverId) {
        bookingRepository.findById(bookingId).orElseThrow();
        bookingDeclineRepository.findByBookingIdAndDriverId(bookingId, driverId)
                .orElseGet(() -> {
                    BookingDecline decline = new BookingDecline();
                    decline.setBookingId(bookingId);
                    decline.setDriverId(driverId);
                    return bookingDeclineRepository.save(decline);
                });
        return Map.of("status", "DECLINED");
    }

    @PutMapping("/{bookingId}/complete/{driverId}")
    public Map<String, Object> completeBooking(@PathVariable Long bookingId,
                                               @PathVariable Long driverId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return idempotency.execute(idemKey,
                "PUT /api/bookings/" + bookingId + "/complete/" + driverId,
                () -> doCompleteBooking(bookingId, driverId));
    }

    private Map<String, Object> doCompleteBooking(Long bookingId, Long driverId) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        Driver driver = driverRepository.findById(driverId).orElseThrow();

        if (!driverId.equals(booking.getDriverId())) {
            return Map.of("status", "FORBIDDEN");
        }

        if ("COMPLETED".equals(booking.getStatus())) {
            return Map.of("status", "ALREADY_COMPLETED");
        }

        booking.setStatus("COMPLETED");
        bookingRepository.save(booking);

        double earned = (booking.getFare() != null ? booking.getFare() : 0.0) + (booking.getTip() != null ? booking.getTip() : 0.0);
        driver.setBalance(driver.getBalance() + earned);
        driver.setTripsCompleted(driver.getTripsCompleted() + 1);
        driverRepository.save(driver);

        Wallet wallet = walletRepository.findByUserIdAndRole(booking.getCommuterId(), "COMMUTER")
                .orElseGet(() -> {
                    Wallet newWallet = new Wallet();
                    newWallet.setUserId(booking.getCommuterId());
                    newWallet.setRole("COMMUTER");
                    newWallet.setBalance(0.0);
                    newWallet.setTotalTrips(0);
                    return walletRepository.save(newWallet);
                });
        wallet.setTotalTrips(wallet.getTotalTrips() + 1);
        walletRepository.save(wallet);

        // Trip lifecycle: COMPLETED. Downstream analytics / payment-risk /
        // trust-score components can subscribe to this event later.
        agentEvents.publish(agentEvents.tripCompleted(
                booking.getId(), driverId, booking.getCommuterId(),
                booking.getFare(), booking.getTip()));
        slo.bookingCompleted();

        return Map.of("status", "COMPLETED", "earned", earned);
    }

    @PutMapping("/{bookingId}/cancel")
    public String cancelBooking(@PathVariable Long bookingId, @RequestParam String role) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        if ("CANCELLED".equals(booking.getStatus())) {
            return "Trip already cancelled.";
        }

        if ("DRIVER".equals(role) && "ACCEPTED".equals(booking.getStatus())) {
            double refund = (booking.getFare() != null ? booking.getFare() : 0.0) + (booking.getTip() != null ? booking.getTip() : 0.0);
            Wallet wallet = walletRepository.findByUserIdAndRole(booking.getCommuterId(), "COMMUTER")
                    .orElseGet(() -> {
                        Wallet newWallet = new Wallet();
                        newWallet.setUserId(booking.getCommuterId());
                        newWallet.setRole("COMMUTER");
                        newWallet.setBalance(0.0);
                        newWallet.setTotalTrips(0);
                        return walletRepository.save(newWallet);
                    });
            wallet.setBalance(wallet.getBalance() + refund);
            walletRepository.save(wallet);
        }

        booking.setStatus("CANCELLED");
        bookingRepository.save(booking);
        slo.bookingCancelled(role);

        DashboardNotification n = new DashboardNotification();
        if (role.equals("DRIVER")) {
            n.setUserId(booking.getCommuterId());
            n.setRole("COMMUTER");
            n.setMessage("Your driver has cancelled the trip.");
        } else if (booking.getDriverId() != null) {
            n.setUserId(booking.getDriverId());
            n.setRole("DRIVER");
            n.setMessage("The commuter has cancelled their trip request.");
        }
        notificationRepository.save(n);

        return "Trip cancelled successfully.";
    }
}
