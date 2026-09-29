package com.etekisi.etekisi.service;

import com.etekisi.etekisi.agent.event.AgentEventPublisher;
import com.etekisi.etekisi.config.SloMetrics;
import com.etekisi.etekisi.dto.api.ApiRecords.EarningsResponse;
import com.etekisi.etekisi.dto.api.ApiRecords.TripCompletedResponse;
import com.etekisi.etekisi.dto.api.BookingDto;
import com.etekisi.etekisi.dto.api.CreateBookingRequest;
import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.BookingDeclineRepository;
import com.etekisi.etekisi.repository.BookingRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.UserRepository;
import com.etekisi.etekisi.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Booking lifecycle and the money that moves with it. Every state change
 * locks the booking row first, then (in this order) the driver row and the
 * commuter wallet, so concurrent requests serialise instead of double-charging.
 *
 * <pre>
 *   PENDING --accept--> ACCEPTED --complete--> COMPLETED
 *      |                   |
 *      +------cancel-------+--> CANCELLED   (ACCEPTED -> CANCELLED refunds the commuter)
 * </pre>
 */
@Service
public class BookingService {

    private final BookingRepository bookings;
    private final DriverRepository drivers;
    private final UserRepository users;
    private final BookingDeclineRepository declines;
    private final WalletService wallets;
    private final LedgerService ledger;
    private final NotificationService notifications;
    private final AgentEventPublisher events;
    private final SloMetrics slo;
    private final BigDecimal maxFare;

    public BookingService(BookingRepository bookings,
                          DriverRepository drivers,
                          UserRepository users,
                          BookingDeclineRepository declines,
                          WalletService wallets,
                          LedgerService ledger,
                          NotificationService notifications,
                          AgentEventPublisher events,
                          SloMetrics slo,
                          @Value("${tekisi.booking.max-fare:1000.00}") BigDecimal maxFare) {
        this.bookings = bookings;
        this.drivers = drivers;
        this.users = users;
        this.declines = declines;
        this.wallets = wallets;
        this.ledger = ledger;
        this.notifications = notifications;
        this.events = events;
        this.slo = slo;
        this.maxFare = maxFare;
    }

    // ----------------------------------------------------------------- reads --

    @Transactional(readOnly = true)
    public List<BookingDto> forCommuter(Long commuterId) {
        return withParticipants(bookings.findByCommuterIdOrderByIdDesc(commuterId));
    }

    @Transactional(readOnly = true)
    public List<BookingDto> forDriver(Long driverId) {
        return withParticipants(bookings.findByDriverIdOrderByIdDesc(driverId));
    }

    /** Open requests a driver can still take: no contact details until they accept. */
    @Transactional(readOnly = true)
    public List<BookingDto> pendingFor(Long driverId) {
        return bookings.findPendingNotDeclinedBy(driverId).stream().map(BookingDto::from).toList();
    }

    @Transactional(readOnly = true)
    public EarningsResponse earnings(Long driverId) {
        Driver driver = drivers.findById(driverId).orElseThrow(() -> ApiException.notFound("Driver"));
        List<Booking> completed = bookings.findByDriverIdAndStatus(driverId, BookingStatus.COMPLETED);
        BigDecimal total = completed.stream().map(Booking::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        completed.sort(Comparator.comparing(Booking::getId).reversed());
        return new EarningsResponse(Money.of(total), completed.size(), driver.getBalance(),
                completed.stream().map(BookingDto::from).toList());
    }

    /** Returns the booking if the caller is its commuter or assigned driver (or staff); otherwise 403. */
    @Transactional(readOnly = true)
    public Booking requireParticipant(Long bookingId, Long callerId, Role callerRole) {
        Booking b = bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        if (!isParticipant(b, callerId, callerRole) && !callerRole.isStaff()) {
            throw new AccessDeniedException("Not a participant in this booking");
        }
        return b;
    }

    // ---------------------------------------------------------------- writes --

    @Transactional
    public BookingDto create(Long commuterId, CreateBookingRequest req) {
        if (!users.existsById(commuterId)) {
            throw ApiException.notFound("Commuter");
        }
        Booking b = new Booking();
        b.setCommuterId(commuterId);
        b.setPickupLocation(req.pickupLocation().trim());
        b.setDestination(req.destination().trim());
        b.setScheduledTime(req.scheduledTime());
        b.setTip(req.tip() != null ? Money.of(req.tip()) : null);
        b.setStatus(BookingStatus.PENDING);
        Booking saved = bookings.save(b);

        // Advisory: agent layer subscribes via BookingCreatedEvent. The agent
        // never mutates booking state — it only writes an audit row.
        events.publish(events.bookingCreated(saved.getId(), commuterId,
                saved.getPickupLocation(), saved.getDestination(), null, null));
        slo.bookingCreated();
        return BookingDto.from(saved);
    }

    /**
     * The driver sets the fare and takes the trip. The commuter's wallet is
     * charged fare + tip immediately; if it can't cover that, the booking
     * stays PENDING (another driver may offer less) and the commuter is told.
     */
    @Transactional
    public BookingDto accept(Long bookingId, Long driverId, BigDecimal requestedFare) {
        BigDecimal fare = Money.requireAmount(requestedFare, null, maxFare, "fare");

        Booking b = lockBooking(bookingId);
        if (b.getStatus() != BookingStatus.PENDING) {
            throw ApiException.conflict("BOOKING_NOT_PENDING", "This booking is no longer available.");
        }
        Driver driver = drivers.findById(driverId).orElseThrow(() -> ApiException.notFound("Driver"));
        User commuter = users.findById(b.getCommuterId()).orElseThrow(() -> ApiException.notFound("Commuter"));

        BigDecimal tip = b.getTip() != null ? b.getTip() : BigDecimal.ZERO;
        BigDecimal total = Money.of(fare.add(tip));
        Wallet wallet = wallets.lockOrCreate(commuter.getId());

        if (wallet.getBalance().compareTo(total) < 0) {
            notifications.sendIndependently(commuter.getId(), Role.COMMUTER,
                    "A driver offered R" + total + " for your trip to " + b.getDestination()
                            + ", but your wallet balance is R" + wallet.getBalance() + ". Top up to accept offers.");
            throw ApiException.conflict("INSUFFICIENT_FUNDS",
                    "The commuter's wallet balance is too low for this fare.",
                    Map.of("required", total, "balance", wallet.getBalance()));
        }

        wallets.debit(wallet, total, WalletTransaction.Type.FARE_DEBIT, b.getId());
        b.setDriverId(driverId);
        b.setFare(fare);
        b.setStatus(BookingStatus.ACCEPTED);

        // Trip just transitioned PENDING -> ACCEPTED. Agent listeners can now
        // start collecting GPS pings and running safety evaluations.
        events.publish(events.tripStarted(b.getId(), driverId, b.getCommuterId(), fare.doubleValue()));

        notifications.send(commuter.getId(), Role.COMMUTER,
                "Driver " + driver.getFullName() + " accepted your trip! Fare: R" + total + " (paid from your wallet).");
        notifications.send(driverId, Role.DRIVER,
                "Trip confirmed! You are scheduled for " + b.getPickupLocation() + " to " + b.getDestination() + ".");
        return BookingDto.from(b, driver, commuter);
    }

    @Transactional
    public void decline(Long bookingId, Long driverId) {
        if (!bookings.existsById(bookingId)) {
            throw ApiException.notFound("Booking");
        }
        if (declines.findByBookingIdAndDriverId(bookingId, driverId).isEmpty()) {
            BookingDecline d = new BookingDecline();
            d.setBookingId(bookingId);
            d.setDriverId(driverId);
            declines.save(d);
        }
    }

    @Transactional
    public TripCompletedResponse complete(Long bookingId, Long driverId) {
        Booking b = lockBooking(bookingId);
        if (!driverId.equals(b.getDriverId())) {
            throw ApiException.forbidden("NOT_ASSIGNED_DRIVER", "Only the assigned driver can complete this trip.");
        }
        if (b.getStatus() == BookingStatus.COMPLETED) {
            throw ApiException.conflict("ALREADY_COMPLETED", "This trip is already completed.");
        }
        if (b.getStatus() != BookingStatus.ACCEPTED) {
            throw ApiException.conflict("BOOKING_NOT_ACCEPTED", "Only an accepted trip can be completed.");
        }
        b.setStatus(BookingStatus.COMPLETED);

        Driver driver = drivers.findByIdForUpdate(driverId).orElseThrow(() -> ApiException.notFound("Driver"));
        BigDecimal earned = Money.of(b.total());
        driver.setBalance(Money.of(driver.getBalance().add(earned)));
        driver.setTripsCompleted(driver.getTripsCompleted() + 1);
        ledger.record(driverId, Role.DRIVER, WalletTransaction.Type.TRIP_EARNING,
                earned, driver.getBalance(), b.getId(), null, null);

        wallets.incrementTrips(b.getCommuterId());

        // Trip lifecycle: COMPLETED. Downstream analytics / payment-risk /
        // trust-score components can subscribe to this event later.
        events.publish(events.tripCompleted(b.getId(), driverId, b.getCommuterId(),
                toDouble(b.getFare()), toDouble(b.getTip())));
        slo.bookingCompleted();

        notifications.send(b.getCommuterId(), Role.COMMUTER,
                "You have arrived at " + b.getDestination() + ". Thanks for riding with e-Tekisi!");
        User commuter = users.findById(b.getCommuterId()).orElse(null);
        return new TripCompletedResponse("COMPLETED", BookingDto.from(b, driver, commuter), earned);
    }

    /**
     * Commuter, assigned driver or staff may cancel. Cancelling an ACCEPTED
     * trip refunds the full fare + tip to the commuter, whoever cancels.
     */
    @Transactional
    public BookingDto cancel(Long bookingId, Long callerId, Role callerRole) {
        Booking b = lockBooking(bookingId);
        if (!isParticipant(b, callerId, callerRole) && !callerRole.isStaff()) {
            throw new AccessDeniedException("Not a participant in this booking");
        }
        if (b.getStatus() == BookingStatus.CANCELLED) {
            throw ApiException.conflict("ALREADY_CANCELLED", "This trip is already cancelled.");
        }
        if (b.getStatus() == BookingStatus.COMPLETED) {
            throw ApiException.conflict("BOOKING_COMPLETED", "A completed trip can't be cancelled.");
        }

        BigDecimal refund = null;
        if (b.getStatus() == BookingStatus.ACCEPTED) {
            refund = Money.of(b.total());
            Wallet wallet = wallets.lockOrCreate(b.getCommuterId());
            wallets.credit(wallet, refund, WalletTransaction.Type.FARE_REFUND, b.getId(), null);
        }
        b.setStatus(BookingStatus.CANCELLED);
        slo.bookingCancelled(callerRole.name());

        String refundNote = refund != null ? " R" + refund + " has been refunded to your wallet." : "";
        if (callerRole == Role.COMMUTER) {
            notifications.send(b.getDriverId(), Role.DRIVER,
                    "The commuter has cancelled the trip from " + b.getPickupLocation() + ".");
            if (refund != null) {
                notifications.send(b.getCommuterId(), Role.COMMUTER, "Trip cancelled." + refundNote);
            }
        } else {
            String who = callerRole == Role.DRIVER ? "Your driver" : "Support";
            notifications.send(b.getCommuterId(), Role.COMMUTER, who + " has cancelled the trip." + refundNote);
            if (callerRole.isStaff()) {
                notifications.send(b.getDriverId(), Role.DRIVER, "Support has cancelled your trip #" + b.getId() + ".");
            }
        }
        return BookingDto.from(b);
    }

    // --------------------------------------------------------------- helpers --

    private Booking lockBooking(Long bookingId) {
        return bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
    }

    private static boolean isParticipant(Booking b, Long callerId, Role callerRole) {
        return (callerRole == Role.COMMUTER && callerId.equals(b.getCommuterId()))
                || (callerRole == Role.DRIVER && callerId.equals(b.getDriverId()));
    }

    /** Adds contact details to bookings that have been accepted; loads people in two queries, not one per row. */
    private List<BookingDto> withParticipants(List<Booking> list) {
        Set<Long> driverIds = new HashSet<>();
        Set<Long> commuterIds = new HashSet<>();
        for (Booking b : list) {
            if (sharesContacts(b)) {
                if (b.getDriverId() != null) driverIds.add(b.getDriverId());
                commuterIds.add(b.getCommuterId());
            }
        }
        Map<Long, Driver> driverMap = drivers.findAllById(driverIds).stream()
                .collect(Collectors.toMap(Driver::getId, Function.identity()));
        Map<Long, User> commuterMap = users.findAllById(commuterIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return list.stream()
                .map(b -> sharesContacts(b)
                        ? BookingDto.from(b, driverMap.get(b.getDriverId()), commuterMap.get(b.getCommuterId()))
                        : BookingDto.from(b))
                .toList();
    }

    private static boolean sharesContacts(Booking b) {
        return b.getStatus() == BookingStatus.ACCEPTED || b.getStatus() == BookingStatus.COMPLETED;
    }

    private static Double toDouble(BigDecimal v) {
        return v != null ? v.doubleValue() : null;
    }
}
