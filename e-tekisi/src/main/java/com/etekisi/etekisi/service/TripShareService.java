package com.etekisi.etekisi.service;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.*;
import com.etekisi.etekisi.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * "Share my trip": the commuter of an accepted trip creates a link for
 * someone they trust. Whoever has the link sees the taxi's live position,
 * its plate and the driver's first name until the trip ends, the link
 * expires ({@code tekisi.trip-share.ttl-hours}, 12) or the commuter stops
 * sharing. Nothing else about either person is shown.
 */
@Service
public class TripShareService {

    private static final Logger log = LoggerFactory.getLogger(TripShareService.class);
    private static final Set<BookingStatus> LIVE = EnumSet.of(BookingStatus.ACCEPTED);

    public record Created(String url, String token, Instant expiresAt) {}

    private final SecureRandom random = new SecureRandom();
    private final TripShareRepository shares;
    private final BookingRepository bookings;
    private final DriverRepository drivers;
    private final UserRepository users;
    private final DriverLocationRepository locations;
    private final String publicUrl;
    private final Duration ttl;

    public TripShareService(TripShareRepository shares, BookingRepository bookings, DriverRepository drivers,
                            UserRepository users, DriverLocationRepository locations,
                            @Value("${tekisi.public-url:http://localhost:3000}") String publicUrl,
                            @Value("${tekisi.trip-share.ttl-hours:12}") long ttlHours) {
        this.shares = shares;
        this.bookings = bookings;
        this.drivers = drivers;
        this.users = users;
        this.locations = locations;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.ttl = Duration.ofHours(ttlHours);
    }

    @Transactional
    public Created create(Long bookingId, Long commuterId) {
        Booking b = ownTrip(bookingId, commuterId);
        if (!LIVE.contains(b.getStatus())) {
            throw ApiException.conflict("TRIP_NOT_ACTIVE", "You can share a trip once a driver has been confirmed.");
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        TripShare s = new TripShare();
        s.setBookingId(bookingId);
        s.setCreatedBy(commuterId);
        s.setTokenHash(sha256(token));
        s.setExpiresAt(Instant.now().plus(ttl));
        shares.save(s);
        log.info("trip.shared bookingId={} shareId={}", bookingId, s.getId());
        return new Created(publicUrl + "/share/" + token, token, s.getExpiresAt());
    }

    /** Stops every link for this trip. */
    @Transactional
    public int revoke(Long bookingId, Long commuterId) {
        ownTrip(bookingId, commuterId);
        List<TripShare> live = shares.findByBookingIdAndRevokedAtIsNull(bookingId);
        live.forEach(s -> s.setRevokedAt(Instant.now()));
        return live.size();
    }

    /** What the person with the link sees. The same 404 for unknown, expired and revoked links. */
    @Transactional(readOnly = true)
    public Map<String, Object> view(String token) {
        TripShare s = shares.findByTokenHash(sha256(token == null ? "" : token))
                .filter(x -> x.getRevokedAt() == null && x.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.notFound("Shared trip"));
        Booking b = bookings.findById(s.getBookingId()).orElseThrow(() -> ApiException.notFound("Shared trip"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commuterFirstName", users.findById(b.getCommuterId()).map(u -> firstName(u.getFullName())).orElse(null));
        out.put("pickup", b.getPickupLocation());
        out.put("destination", b.getDestination());
        out.put("expiresAt", s.getExpiresAt().toString());
        if (!LIVE.contains(b.getStatus())) {
            // Finished or cancelled: say so, without the driver or any position.
            out.put("status", b.getStatus() == BookingStatus.COMPLETED ? "COMPLETED" : "ENDED");
            return out;
        }
        out.put("status", "IN_PROGRESS");
        Driver d = b.getDriverId() == null ? null : drivers.findById(b.getDriverId()).orElse(null);
        if (d != null) {
            out.put("driverFirstName", firstName(d.getFullName()));
            out.put("taxiPlate", d.getTaxiPlate());
            locations.findById(d.getId()).ifPresent(l -> {
                Map<String, Object> pos = new LinkedHashMap<>();
                pos.put("lat", l.getLat());
                pos.put("lng", l.getLng());
                pos.put("updatedAt", l.getLastUpdated() == null ? null
                        : l.getLastUpdated().atZone(ZoneId.systemDefault()).toInstant().toString());
                pos.put("stale", l.getLastUpdated() == null || l.getLastUpdated().isBefore(LocalDateTime.now().minusMinutes(5)));
                out.put("taxiPosition", pos);
            });
        }
        return out;
    }

    private Booking ownTrip(Long bookingId, Long commuterId) {
        Booking b = bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        if (!commuterId.equals(b.getCommuterId())) throw ApiException.notFound("Booking");
        return b;
    }

    private static String firstName(String full) {
        if (full == null || full.isBlank()) return null;
        return full.trim().split("\\s+")[0];
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SchedulerLock(name = "trip-share-purge", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${tekisi.trip-share.purge-cron:0 50 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int n = shares.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(7)));
        if (n > 0) log.info("trip.shares.purged deleted={}", n);
    }
}
