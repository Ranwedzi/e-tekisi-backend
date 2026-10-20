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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Booking lifecycle and the money that moves with it. Every state change
 * locks the booking row first, then (in this order) the driver row and the
 * commuter wallet, so concurrent requests serialise instead of double-charging.
 *
 * <pre>
 *   PENDING --driver offers a fare--> OFFERED --commuter accepts--> ACCEPTED --complete--> COMPLETED
 *      ^                                 |      (wallet charged)
 *      +-- commuter declines, driver withdraws, or the offer expires
 *
 *   cancel: PENDING/OFFERED -> CANCELLED (no money), ACCEPTED -> CANCELLED (commuter refunded)
 * </pre>
 *
 * The commuter is never charged without saying yes to the exact amount.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final DriverRepository drivers;
    private final UserRepository users;
    private final BookingDeclineRepository declines;
    private final WalletService wallets;
    private final LedgerService ledger;
    private final NotificationService notifications;
    private final AgentEventPublisher events;
    private final SloMetrics slo;
    /** Runs in its own, committed transaction (REQUIRES_NEW). */
    private final TransactionTemplate tx;
    private final BigDecimal maxFare;
    private final Duration offerTtl;

    public BookingService(BookingRepository bookings,
                          DriverRepository drivers,
                          UserRepository users,
                          BookingDeclineRepository declines,
                          WalletService wallets,
                          LedgerService ledger,
                          NotificationService notifications,
                          AgentEventPublisher events,
                          SloMetrics slo,
                          PlatformTransactionManager txManager,
                          @Value("${tekisi.booking.max-fare:1000.00}") BigDecimal maxFare,
                          @Value("${tekisi.booking.offer-ttl-seconds:120}") long offerTtlSeconds) {
        this.bookings = bookings;
        this.drivers = drivers;
        this.users = users;
        this.declines = declines;
        this.wallets = wallets;
        this.ledger = ledger;
        this.notifications = notifications;
        this.events = events;
        this.slo = slo;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxFare = maxFare;
        this.offerTtl = Duration.ofSeconds(offerTtlSeconds);
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

    /** Open requests a driver can still offer on: no contact details. */
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
        User commuterAccount = users.findById(commuterId).orElseThrow(() -> ApiException.notFound("Commuter"));
        AccountSecurityService.requireVerified(commuterAccount);
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
     * A driver offers to do the trip for {@code requestedFare}. Nothing is
     * charged: the booking is held for this driver until the commuter accepts,
     * declines, or the offer expires ({@code tekisi.booking.offer-ttl-seconds}).
     */
    @Transactional
    public BookingDto offer(Long bookingId, Long driverId, BigDecimal requestedFare) {
        BigDecimal fare = Money.requireAmount(requestedFare, null, maxFare, "fare");

        expireIfStaleCommitted(bookingId);
        Booking b = lockBooking(bookingId);
        if (b.getStatus() != BookingStatus.PENDING) {
            throw ApiException.conflict("BOOKING_NOT_PENDING", "This booking is no longer available.");
        }
        Driver driver = drivers.findById(driverId).orElseThrow(() -> ApiException.notFound("Driver"));
        AccountSecurityService.requireVerified(driver);
        if (!driver.isApproved()) {
            throw ApiException.conflict("DRIVER_NOT_APPROVED", "Your driver account must be approved before you can send offers.");
        }
        if (!driver.isActive()) {
            throw ApiException.conflict("DRIVER_OFFLINE", "Go online to send fare offers.");
        }
        User commuter = users.findById(b.getCommuterId()).orElseThrow(() -> ApiException.notFound("Commuter"));

        b.setDriverId(driverId);
        b.setFare(fare);
        b.setStatus(BookingStatus.OFFERED);
        b.setOfferExpiresAt(Instant.now().plus(offerTtl));

        notifications.send(commuter.getId(), Role.COMMUTER,
                driver.getFullName() + " (" + driver.getTaxiPlate() + ") offers to take you to " + b.getDestination()
                        + " for R" + Money.of(b.total()) + ". Accept within " + offerTtl.toMinutes() + " minutes.");
        return BookingDto.from(b, driver, commuter, false);
    }

    /**
     * The commuter accepts the outstanding offer: fare + tip is charged now
     * and the trip starts. If the wallet is short, nothing changes and the
     * offer stays open until it expires, so the commuter can top up and retry.
     */
    @Transactional
    public BookingDto acceptOffer(Long bookingId, Long commuterId) {
        expireIfStaleCommitted(bookingId);
        Booking b = lockBooking(bookingId);
        if (!commuterId.equals(b.getCommuterId())) {
            throw new AccessDeniedException("Not your booking");
        }
        if (b.getStatus() != BookingStatus.OFFERED || isLapsed(b)) {
            throw ApiException.conflict("OFFER_NOT_AVAILABLE", "This offer is no longer available.");
        }
        Driver driver = drivers.findById(b.getDriverId()).orElseThrow(() -> ApiException.notFound("Driver"));
        User commuter = users.findById(commuterId).orElseThrow(() -> ApiException.notFound("Commuter"));

        BigDecimal total = Money.of(b.total());
        Wallet wallet = wallets.lockOrCreate(commuterId);
        if (wallet.getBalance().compareTo(total) < 0) {
            throw ApiException.conflict("INSUFFICIENT_FUNDS",
                    "Your wallet balance is too low for this fare. Top up and accept again.",
                    Map.of("required", total, "balance", wallet.getBalance()));
        }

        wallets.debit(wallet, total, WalletTransaction.Type.FARE_DEBIT, b.getId());
        b.setStatus(BookingStatus.ACCEPTED);
        b.setOfferExpiresAt(null);

        // Trip just started. Agent listeners can now collect GPS pings and run safety checks.
        events.publish(events.tripStarted(b.getId(), driver.getId(), commuterId, b.getFare().doubleValue()));

        notifications.send(driver.getId(), Role.DRIVER,
                commuter.getFullName() + " accepted your offer of R" + total + ". Pick up at " + b.getPickupLocation() + ".");
        return BookingDto.from(b, driver, commuter, true);
    }

    /** The commuter turns the offer down; the booking goes back to PENDING for other drivers. */
    @Transactional
    public BookingDto declineOffer(Long bookingId, Long commuterId) {
        expireIfStaleCommitted(bookingId);
        Booking b = lockBooking(bookingId);
        if (!commuterId.equals(b.getCommuterId())) {
            throw new AccessDeniedException("Not your booking");
        }
        if (b.getStatus() != BookingStatus.OFFERED || isLapsed(b)) {
            throw ApiException.conflict("OFFER_NOT_AVAILABLE", "This offer is no longer available.");
        }
        Long driverId = b.getDriverId();
        BigDecimal offered = Money.of(b.total());
        reopen(b);
        notifications.send(driverId, Role.DRIVER,
                "Your offer of R" + offered + " for " + b.getPickupLocation() + " to " + b.getDestination() + " was declined.");
        return BookingDto.from(b);
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
        return new TripCompletedResponse("COMPLETED", BookingDto.from(b, driver, commuter, true), earned);
    }

    /**
     * Commuter, assigned driver or staff may cancel. Cancelling an ACCEPTED
     * trip refunds the full fare + tip to the commuter, whoever cancels.
     * A driver "cancelling" while their offer is still OFFERED withdraws the
     * offer instead: the booking goes back to PENDING for other drivers.
     */
    @Transactional
    public BookingDto cancel(Long bookingId, Long callerId, Role callerRole) {
        expireIfStaleCommitted(bookingId);
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

        if (b.getStatus() == BookingStatus.OFFERED && callerRole == Role.DRIVER) {
            reopen(b);
            notifications.send(b.getCommuterId(), Role.COMMUTER,
                    "The driver withdrew their offer. We're still looking for a driver for your trip to " + b.getDestination() + ".");
            return BookingDto.from(b);
        }

        BigDecimal refund = null;
        if (b.getStatus() == BookingStatus.ACCEPTED) {
            refund = Money.of(b.total());
            Wallet wallet = wallets.lockOrCreate(b.getCommuterId());
            wallets.credit(wallet, refund, WalletTransaction.Type.FARE_REFUND, b.getId(), null);
        }
        b.setStatus(BookingStatus.CANCELLED);
        b.setOfferExpiresAt(null);
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

    /**
     * Returns lapsed offers to PENDING so other drivers can take the trip.
     * Requests also expire lazily when touched, so this only keeps lists tidy.
     */
    @Scheduled(fixedDelayString = "${tekisi.booking.offer-expiry-check-ms:15000}")
    public void expireStaleOffers() {
        List<Booking> stale = bookings.findByStatusAndOfferExpiresAtBefore(BookingStatus.OFFERED, Instant.now());
        for (Booking s : stale) {
            try {
                expireIfStaleCommitted(s.getId());
            } catch (RuntimeException e) {
                log.warn("booking.offer.expire_failed bookingId={} error={}", s.getId(), e.getMessage());
            }
        }
    }

    // --------------------------------------------------------------- helpers --

    private Booking lockBooking(Long bookingId) {
        return bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
    }

    /**
     * If the booking's offer has lapsed, reopen it and tell both sides, in a
     * transaction of its own. Committing separately matters: the caller is
     * about to throw "offer not available", and that rollback must not undo
     * the reopen. Call it before the caller takes the booking lock.
     */
    private void expireIfStaleCommitted(Long bookingId) {
        tx.executeWithoutResult(status -> bookings.findByIdForUpdate(bookingId).ifPresent(b -> {
            if (b.getStatus() == BookingStatus.OFFERED && isLapsed(b)) {
                reopenLapsed(b);
            }
        }));
    }

    private static boolean isLapsed(Booking b) {
        return b.getOfferExpiresAt() != null && !b.getOfferExpiresAt().isAfter(Instant.now());
    }

    private void reopenLapsed(Booking b) {
        Long driverId = b.getDriverId();
        reopen(b);
        notifications.send(driverId, Role.DRIVER,
                "Your offer for " + b.getPickupLocation() + " to " + b.getDestination() + " expired before the commuter answered.");
        notifications.send(b.getCommuterId(), Role.COMMUTER,
                "A fare offer for your trip to " + b.getDestination() + " expired. We're still looking for a driver.");
    }

    private static void reopen(Booking b) {
        b.setStatus(BookingStatus.PENDING);
        b.setDriverId(null);
        b.setFare(null);
        b.setOfferExpiresAt(null);
    }

    private static boolean isParticipant(Booking b, Long callerId, Role callerRole) {
        return (callerRole == Role.COMMUTER && callerId.equals(b.getCommuterId()))
                || (callerRole == Role.DRIVER && callerId.equals(b.getDriverId()));
    }

    /** Adds participant details (see BookingDto); loads people in two queries, not one per row. */
    private List<BookingDto> withParticipants(List<Booking> list) {
        Set<Long> driverIds = new HashSet<>();
        Set<Long> commuterIds = new HashSet<>();
        for (Booking b : list) {
            if (sharesNames(b)) {
                if (b.getDriverId() != null) driverIds.add(b.getDriverId());
                commuterIds.add(b.getCommuterId());
            }
        }
        Map<Long, Driver> driverMap = drivers.findAllById(driverIds).stream()
                .collect(Collectors.toMap(Driver::getId, Function.identity()));
        Map<Long, User> commuterMap = users.findAllById(commuterIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return list.stream()
                .map(b -> sharesNames(b)
                        ? BookingDto.from(b, driverMap.get(b.getDriverId()), commuterMap.get(b.getCommuterId()), sharesPhones(b))
                        : BookingDto.from(b))
                .toList();
    }

    private static boolean sharesNames(Booking b) {
        return b.getStatus() == BookingStatus.OFFERED || sharesPhones(b);
    }

    private static boolean sharesPhones(Booking b) {
        return b.getStatus() == BookingStatus.ACCEPTED || b.getStatus() == BookingStatus.COMPLETED;
    }

    private static Double toDouble(BigDecimal v) {
        return v != null ? v.doubleValue() : null;
    }
}
