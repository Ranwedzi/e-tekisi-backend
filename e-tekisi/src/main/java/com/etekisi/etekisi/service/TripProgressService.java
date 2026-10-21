package com.etekisi.etekisi.service;

import com.etekisi.etekisi.agent.insight.InsightCode;
import com.etekisi.etekisi.agent.insight.OperationalInsight;
import com.etekisi.etekisi.agent.insight.OperationalInsightRepository;
import com.etekisi.etekisi.dto.api.BookingDto;
import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.*;
import com.etekisi.etekisi.web.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A trip after the commuter accepts: the driver arrives, the commuter gets in,
 * the trip ends (BookingService.complete), and then each side rates the other.
 * Also the driver's live position for the commuter while they wait.
 */
@Service
public class TripProgressService {

    /** Ratings are taken for a week after the trip. */
    static final Duration RATING_WINDOW = Duration.ofDays(7);
    /** A driver whose average falls below this (after enough trips) is flagged to operators. */
    private static final double LOW_AVERAGE = 3.5;
    private static final int LOW_AVERAGE_MIN_COUNT = 10;

    private final BookingRepository bookings;
    private final DriverRepository drivers;
    private final UserRepository users;
    private final TripRatingRepository ratings;
    private final DriverLocationRepository locations;
    private final NotificationService notifications;
    private final ObjectProvider<OperationalInsightRepository> insights;

    public TripProgressService(BookingRepository bookings, DriverRepository drivers, UserRepository users,
                               TripRatingRepository ratings, DriverLocationRepository locations,
                               NotificationService notifications, ObjectProvider<OperationalInsightRepository> insights) {
        this.bookings = bookings;
        this.drivers = drivers;
        this.users = users;
        this.ratings = ratings;
        this.locations = locations;
        this.notifications = notifications;
        this.insights = insights;
    }

    /** The driver is at the pick-up point. */
    @Transactional
    public BookingDto arrived(Long bookingId, Long driverId) {
        Booking b = assignedTrip(bookingId, driverId);
        if (b.getArrivedAt() == null) {
            b.setArrivedAt(Instant.now());
            Driver d = drivers.findById(driverId).orElse(null);
            notifications.send(b.getCommuterId(), Role.COMMUTER, "Your driver has arrived at " + b.getPickupLocation()
                    + (d != null ? ". Look for " + d.getTaxiPlate() + "." : "."));
        }
        return view(b);
    }

    /** The commuter is on board. From now the trip can't be cancelled for a refund. */
    @Transactional
    public BookingDto start(Long bookingId, Long driverId) {
        Booking b = assignedTrip(bookingId, driverId);
        if (b.getStartedAt() == null) {
            Instant now = Instant.now();
            if (b.getArrivedAt() == null) b.setArrivedAt(now);
            b.setStartedAt(now);
            notifications.send(b.getCommuterId(), Role.COMMUTER, "Your trip to " + b.getDestination() + " has started. Safe travels!");
        }
        return view(b);
    }

    /** Where the taxi is, for the commuter of an accepted trip; null fields if the driver hasn't shared yet. */
    @Transactional(readOnly = true)
    public Map<String, Object> driverPosition(Long bookingId, Long commuterId) {
        Booking b = bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        if (!commuterId.equals(b.getCommuterId()) || b.getStatus() != BookingStatus.ACCEPTED || b.getDriverId() == null) {
            throw ApiException.notFound("Driver position");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        locations.findById(b.getDriverId()).ifPresent(l -> {
            out.put("lat", l.getLat());
            out.put("lng", l.getLng());
            out.put("updatedAt", l.getLastUpdated() == null ? null : l.getLastUpdated().atZone(ZoneId.systemDefault()).toInstant().toString());
            out.put("stale", l.getLastUpdated() == null || l.getLastUpdated().isBefore(LocalDateTime.now().minusMinutes(2)));
        });
        return out;
    }

    /** 1-5 stars and an optional comment, once per side, within a week of a completed trip. */
    @Transactional
    public Map<String, Object> rate(Long bookingId, Long callerId, Role callerRole, int stars, String comment) {
        if (stars < 1 || stars > 5) throw ApiException.badRequest("INVALID_RATING", "Choose 1 to 5 stars.");
        Booking b = bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        boolean commuter = callerRole == Role.COMMUTER && callerId.equals(b.getCommuterId());
        boolean driver = callerRole == Role.DRIVER && callerId.equals(b.getDriverId());
        if (!commuter && !driver) throw ApiException.notFound("Booking");
        if (b.getStatus() != BookingStatus.COMPLETED) {
            throw ApiException.conflict("TRIP_NOT_COMPLETED", "You can rate a trip once it's completed.");
        }
        Instant done = b.getCompletedAt();
        if (done != null && done.plus(RATING_WINDOW).isBefore(Instant.now())) {
            throw ApiException.conflict("RATING_CLOSED", "Ratings close a week after the trip.");
        }
        if (ratings.existsByBookingIdAndRaterRole(bookingId, callerRole)) {
            throw ApiException.conflict("ALREADY_RATED", "You've already rated this trip.");
        }
        TripRating r = new TripRating();
        r.setBookingId(bookingId);
        r.setRaterRole(callerRole);
        r.setRaterId(callerId);
        r.setRateeRole(commuter ? Role.DRIVER : Role.COMMUTER);
        r.setRateeId(commuter ? b.getDriverId() : b.getCommuterId());
        r.setStars((short) stars);
        String c = comment == null ? null : comment.trim();
        r.setComment(c == null || c.isEmpty() ? null : c.length() > 300 ? c.substring(0, 300) : c);
        try {
            ratings.saveAndFlush(r);
        } catch (DataIntegrityViolationException twice) {
            throw ApiException.conflict("ALREADY_RATED", "You've already rated this trip.");
        }

        Double average;
        int count;
        if (commuter) {
            Driver d = drivers.findByIdForUpdate(b.getDriverId()).orElseThrow(() -> ApiException.notFound("Driver"));
            d.setRatingSum(d.getRatingSum() + stars);
            d.setRatingCount(d.getRatingCount() + 1);
            average = d.averageRating();
            count = d.getRatingCount();
            flagIfLow(b, d, stars, r.getComment());
        } else {
            User u = users.findById(b.getCommuterId()).orElseThrow(() -> ApiException.notFound("Commuter"));
            u.setRatingSum(u.getRatingSum() + stars);
            u.setRatingCount(u.getRatingCount() + 1);
            average = u.averageRating();
            count = u.getRatingCount();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "RATED");
        out.put("stars", stars);
        out.put("rateeAverage", average);
        out.put("rateeCount", count);
        return out;
    }

    /** One or two stars, or a low average over enough trips: operators should look. */
    private void flagIfLow(Booking b, Driver d, int stars, String comment) {
        OperationalInsightRepository repo = insights.getIfAvailable();
        if (repo == null) return;
        boolean lowStars = stars <= 2;
        boolean lowAverage = d.getRatingCount() >= LOW_AVERAGE_MIN_COUNT && d.averageRating() != null && d.averageRating() < LOW_AVERAGE;
        if (!lowStars && !lowAverage) return;
        OperationalInsight i = new OperationalInsight();
        i.setBookingId(b.getId());
        i.setDriverId(d.getId());
        i.setCommuterId(b.getCommuterId());
        i.setType(OperationalInsight.Type.ANOMALY);
        i.setCode(InsightCode.DRIVER_LOW_RATING);
        i.setSeverity(OperationalInsight.Severity.WARN);
        String msg = (lowStars ? "Trip rated " + stars + "/5" : "Average fell to " + d.averageRating())
                + " for driver " + d.getFullName() + " (" + d.getTaxiPlate() + ")"
                + (lowStars && comment != null ? ": \"" + comment + "\"" : "")
                + ". Average " + d.averageRating() + " over " + d.getRatingCount() + " ratings.";
        i.setMessage(msg.length() > 512 ? msg.substring(0, 512) : msg);
        i.setCreatedAt(Instant.now());
        repo.save(i);
    }

    private Booking assignedTrip(Long bookingId, Long driverId) {
        Booking b = bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        if (!driverId.equals(b.getDriverId())) {
            throw ApiException.forbidden("NOT_ASSIGNED_DRIVER", "Only the assigned driver can do this.");
        }
        if (b.getStatus() != BookingStatus.ACCEPTED) {
            throw ApiException.conflict("BOOKING_NOT_ACCEPTED", "Only an accepted trip can be updated.");
        }
        return b;
    }

    private BookingDto view(Booking b) {
        Driver d = b.getDriverId() == null ? null : drivers.findById(b.getDriverId()).orElse(null);
        User c = users.findById(b.getCommuterId()).orElse(null);
        return BookingDto.from(b, d, c, true);
    }
}
