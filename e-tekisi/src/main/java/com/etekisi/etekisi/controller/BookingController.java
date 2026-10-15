package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.agent.idempotency.IdempotencyService;
import com.etekisi.etekisi.dto.api.ApiRecords.CreateBookingResponse;
import com.etekisi.etekisi.dto.api.ApiRecords.EarningsResponse;
import com.etekisi.etekisi.dto.api.ApiRecords.TripCompletedResponse;
import com.etekisi.etekisi.dto.api.BookingDto;
import com.etekisi.etekisi.dto.api.CreateBookingRequest;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Booking endpoints. Who the caller is always comes from the JWT; ids in the
 * path are checked against it. Business rules live in {@link BookingService}.
 *
 * <p>create / offer / accept-offer / complete honor the {@code Idempotency-Key} header —
 * repeated requests with the same key return the original response without
 * repeating the side effect.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookings;
    private final IdempotencyService idempotency;
    private final CurrentUser currentUser;

    public BookingController(BookingService bookings, IdempotencyService idempotency, CurrentUser currentUser) {
        this.bookings = bookings;
        this.idempotency = idempotency;
        this.currentUser = currentUser;
    }

    /** The commuter id is taken from the token; any commuterId in the body is ignored. */
    @PostMapping("/create")
    public CreateBookingResponse createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long commuterId = currentUser.requireRole(Role.COMMUTER);
        return idempotency.execute(idemKey, scope(commuterId, "POST /api/bookings/create"),
                CreateBookingResponse.class, () -> {
                    BookingDto saved = bookings.create(commuterId, request);
                    return new CreateBookingResponse("PENDING", saved.id(), saved,
                            "Booking saved. Waiting for a driver to set the fare.");
                });
    }

    /** Open requests for the calling driver, minus the ones they declined. */
    @GetMapping("/pending")
    public List<BookingDto> getPendingBookings() {
        return bookings.pendingFor(currentUser.requireRole(Role.DRIVER));
    }

    @GetMapping("/user/{userId}")
    public List<BookingDto> getMyBookings(@PathVariable Long userId) {
        currentUser.requireSelf(userId, Role.COMMUTER);
        return bookings.forCommuter(userId);
    }

    @GetMapping("/driver/{driverId}")
    public List<BookingDto> getDriverBookings(@PathVariable Long driverId) {
        currentUser.requireSelf(driverId, Role.DRIVER);
        return bookings.forDriver(driverId);
    }

    @GetMapping("/driver/{driverId}/earnings")
    public EarningsResponse getDriverEarnings(@PathVariable Long driverId) {
        currentUser.requireSelf(driverId, Role.DRIVER);
        return bookings.earnings(driverId);
    }

    /**
     * The calling driver offers to do the trip for {@code fare}. Nothing is
     * charged until the commuter accepts (see {@link #acceptOffer}).
     */
    @PutMapping("/{bookingId}/offer")
    public BookingDto offer(@PathVariable Long bookingId,
                            @RequestParam BigDecimal fare,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long driverId = currentUser.requireRole(Role.DRIVER);
        return idempotency.execute(idemKey, scope(driverId, "PUT /api/bookings/" + bookingId + "/offer"),
                BookingDto.class, () -> bookings.offer(bookingId, driverId, fare));
    }

    /** The commuter accepts the driver's offer; the wallet is charged fare + tip now. */
    @PutMapping("/{bookingId}/accept-offer")
    public BookingDto acceptOffer(@PathVariable Long bookingId,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        Long commuterId = currentUser.requireRole(Role.COMMUTER);
        return idempotency.execute(idemKey, scope(commuterId, "PUT /api/bookings/" + bookingId + "/accept-offer"),
                BookingDto.class, () -> bookings.acceptOffer(bookingId, commuterId));
    }

    /** The commuter turns the offer down; the request goes back to other drivers. */
    @PutMapping("/{bookingId}/decline-offer")
    public BookingDto declineOffer(@PathVariable Long bookingId) {
        return bookings.declineOffer(bookingId, currentUser.requireRole(Role.COMMUTER));
    }

    @PostMapping("/{bookingId}/decline/{driverId}")
    public Map<String, Object> declineBooking(@PathVariable Long bookingId, @PathVariable Long driverId) {
        requireCallingDriver(driverId);
        bookings.decline(bookingId, driverId);
        return Map.of("status", "DECLINED");
    }

    @PutMapping("/{bookingId}/complete/{driverId}")
    public TripCompletedResponse completeBooking(@PathVariable Long bookingId,
                                                 @PathVariable Long driverId,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        requireCallingDriver(driverId);
        return idempotency.execute(idemKey, scope(driverId, "PUT /api/bookings/" + bookingId + "/complete"),
                TripCompletedResponse.class, () -> bookings.complete(bookingId, driverId));
    }

    /**
     * Cancel as the calling commuter, assigned driver, or staff. The old
     * {@code ?role=} parameter is ignored: the role comes from the token.
     */
    @PutMapping("/{bookingId}/cancel")
    public BookingDto cancelBooking(@PathVariable Long bookingId) {
        return bookings.cancel(bookingId, currentUser.id(), currentUser.role());
    }

    /** Drivers act only as themselves; staff don't accept or complete trips. */
    private void requireCallingDriver(Long driverId) {
        Long caller = currentUser.requireRole(Role.DRIVER);
        if (!caller.equals(driverId)) {
            throw new AccessDeniedException("Drivers can only act as themselves");
        }
    }

    private static String scope(Long userId, String route) {
        return "u" + userId + ":" + route;
    }
}
