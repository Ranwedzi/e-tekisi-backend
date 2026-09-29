package com.etekisi.etekisi.agent.guardrail;

import com.etekisi.etekisi.agent.trip.TripGpsPing;
import com.etekisi.etekisi.agent.trip.TripGpsPingRepository;
import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Single facade over the authoritative repositories.
 *
 * Agents are FORBIDDEN from injecting repositories directly. Every fact they
 * reason about must come through this service so:
 *   - we know exactly what was consulted (audit)
 *   - freshness rules are enforced uniformly
 *   - missing data is signalled as missing, never imagined
 */
@Service
public class TrustedDataService {

    /** Driver location pings older than this are treated as unknown. */
    public static final Duration DRIVER_LOCATION_TTL = Duration.ofSeconds(30);

    private final BookingRepository bookings;
    private final DriverRepository drivers;
    private final DriverLocationRepository driverLocations;
    private final RankRepository ranks;
    private final RouteRepository routes;
    private final QueueRepository queue;
    private final BookingDeclineRepository declines;
    private final TripGpsPingRepository tripGps;

    public TrustedDataService(BookingRepository bookings,
                              DriverRepository drivers,
                              DriverLocationRepository driverLocations,
                              RankRepository ranks,
                              RouteRepository routes,
                              QueueRepository queue,
                              BookingDeclineRepository declines,
                              TripGpsPingRepository tripGps) {
        this.bookings = bookings;
        this.drivers = drivers;
        this.driverLocations = driverLocations;
        this.ranks = ranks;
        this.routes = routes;
        this.queue = queue;
        this.declines = declines;
        this.tripGps = tripGps;
    }

    public TrustedFact<Booking> getBooking(Long bookingId) {
        if (bookingId == null) return TrustedFact.missing("BookingRepository");
        return bookings.findById(bookingId)
                .map(b -> TrustedFact.of(b, "BookingRepository"))
                .orElseGet(() -> TrustedFact.missing("BookingRepository"));
    }

    public TrustedFact<Driver> getDriver(Long driverId) {
        if (driverId == null) return TrustedFact.missing("DriverRepository");
        return drivers.findById(driverId)
                .map(d -> TrustedFact.of(d, "DriverRepository"))
                .orElseGet(() -> TrustedFact.missing("DriverRepository"));
    }

    /** Returns location only if fresh; otherwise empty fact. */
    public TrustedFact<DriverLocation> getFreshDriverLocation(Long driverId) {
        if (driverId == null) return TrustedFact.missing("DriverLocationRepository");
        Optional<DriverLocation> loc = driverLocations.findById(driverId);
        if (loc.isEmpty() || loc.get().getLastUpdated() == null) {
            return TrustedFact.missing("DriverLocationRepository");
        }
        TrustedFact<DriverLocation> fact = new TrustedFact<>(
                loc.get(), loc.get().getLastUpdated().atZone(java.time.ZoneId.systemDefault()).toInstant(),
                "DriverLocationRepository");
        return fact.isFresh(DRIVER_LOCATION_TTL) ? fact : TrustedFact.missing("DriverLocationRepository(stale)");
    }

    /** Drivers that have pinged in the last N seconds — never invents drivers. */
    public TrustedFact<List<DriverLocation>> getActiveDriverLocations(Duration window) {
        LocalDateTime since = LocalDateTime.now().minus(window);
        List<DriverLocation> list = driverLocations.findByLastUpdatedAfter(since);
        return TrustedFact.of(list == null ? Collections.emptyList() : list, "DriverLocationRepository");
    }

    public TrustedFact<List<TaxiRank>> getAllRanks() {
        return TrustedFact.of(ranks.findAll(), "RankRepository");
    }

    public TrustedFact<TaxiRank> getRank(Long rankId) {
        if (rankId == null) return TrustedFact.missing("RankRepository");
        return ranks.findById(rankId)
                .map(r -> TrustedFact.of(r, "RankRepository"))
                .orElseGet(() -> TrustedFact.missing("RankRepository"));
    }

    public TrustedFact<Route> resolveRoute(String start, String destination) {
        if (start == null || destination == null) return TrustedFact.missing("RouteRepository");
        return routes.findByStartLocationIgnoreCaseAndDestinationIgnoreCase(start, destination)
                .map(r -> TrustedFact.of(r, "RouteRepository"))
                .orElseGet(() -> TrustedFact.missing("RouteRepository"));
    }

    public TrustedFact<List<QueueTicket>> getQueueAtRank(String rankName) {
        if (rankName == null) return TrustedFact.missing("QueueRepository");
        return TrustedFact.of(
                queue.findByTaxiRankAndStatusOrderByJoinedAtAsc(rankName, "WAITING"),
                "QueueRepository");
    }

    /** -1 if driver isn't queued; 0-based otherwise. */
    public TrustedFact<Integer> getDriverQueuePosition(Long driverId, String rankName) {
        TrustedFact<List<QueueTicket>> q = getQueueAtRank(rankName);
        if (!q.isPresent()) return TrustedFact.missing("QueueRepository");
        List<QueueTicket> tickets = q.value();
        for (int i = 0; i < tickets.size(); i++) {
            if (driverId.equals(tickets.get(i).getDriverId())) {
                return TrustedFact.of(i, "QueueRepository");
            }
        }
        return TrustedFact.of(-1, "QueueRepository");
    }

    public TrustedFact<Integer> countRecentDeclines(Long driverId) {
        if (driverId == null) return TrustedFact.missing("BookingDeclineRepository");
        return TrustedFact.of(declines.findByDriverId(driverId).size(), "BookingDeclineRepository");
    }

    public TrustedFact<List<Booking>> getPendingBookings() {
        return TrustedFact.of(bookings.findByStatus("PENDING"), "BookingRepository");
    }

    /** Active = ACCEPTED (driver assigned, trip in progress). */
    public TrustedFact<List<Booking>> getActiveBookingsForDriver(Long driverId) {
        if (driverId == null) return TrustedFact.missing("BookingRepository");
        return TrustedFact.of(
                bookings.findByDriverIdAndStatus(driverId, "ACCEPTED"),
                "BookingRepository");
    }

    /** Trip GPS pings since a given instant — empty list, never null. */
    public TrustedFact<List<TripGpsPing>> getTripPingsSince(Long bookingId, Instant since) {
        if (bookingId == null || since == null) return TrustedFact.missing("TripGpsPingRepository");
        List<TripGpsPing> rows = tripGps.findByBookingIdAndRecordedAtAfterOrderByRecordedAtAsc(bookingId, since);
        return TrustedFact.of(rows == null ? Collections.emptyList() : rows, "TripGpsPingRepository");
    }

    /** Most recent N pings, returned oldest-first for stationary/deviation analysis. */
    public TrustedFact<List<TripGpsPing>> getRecentTripPings(Long bookingId, int limit) {
        if (bookingId == null || limit <= 0) return TrustedFact.missing("TripGpsPingRepository");
        List<TripGpsPing> desc = tripGps.findByBookingIdOrderByRecordedAtDesc(bookingId, PageRequest.of(0, limit));
        if (desc == null || desc.isEmpty()) return TrustedFact.of(List.of(), "TripGpsPingRepository");
        // reverse to chronological order
        List<TripGpsPing> chronological = new java.util.ArrayList<>(desc);
        Collections.reverse(chronological);
        return TrustedFact.of(chronological, "TripGpsPingRepository");
    }
}
