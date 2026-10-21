package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.dto.api.BookingDto;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.TripProgressService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** An accepted trip as it happens, and ratings afterwards. */
@RestController
@RequestMapping("/api/bookings/{bookingId}")
public class TripController {

    public record RatingRequest(@NotNull @Min(1) @Max(5) Integer stars, @Size(max = 300) String comment) {}

    private final TripProgressService trips;
    private final CurrentUser currentUser;

    public TripController(TripProgressService trips, CurrentUser currentUser) {
        this.trips = trips;
        this.currentUser = currentUser;
    }

    /** Driver: "I'm at the pick-up point" (the commuter is notified). */
    @PutMapping("/arrived")
    public BookingDto arrived(@PathVariable Long bookingId) {
        return trips.arrived(bookingId, currentUser.requireRole(Role.DRIVER));
    }

    /** Driver: the commuter is on board. */
    @PutMapping("/start")
    public BookingDto start(@PathVariable Long bookingId) {
        return trips.start(bookingId, currentUser.requireRole(Role.DRIVER));
    }

    /** Commuter of an accepted trip: where the taxi is now. */
    @GetMapping("/driver-position")
    public Map<String, Object> driverPosition(@PathVariable Long bookingId) {
        return trips.driverPosition(bookingId, currentUser.requireRole(Role.COMMUTER));
    }

    /** Commuter or driver of a completed trip rates the other, once. */
    @PostMapping("/rating")
    public Map<String, Object> rate(@PathVariable Long bookingId, @Valid @RequestBody RatingRequest body) {
        return trips.rate(bookingId, currentUser.id(), currentUser.role(), body.stars(), body.comment());
    }
}
