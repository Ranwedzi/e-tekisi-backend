package com.etekisi.etekisi.push;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.BookingRepository;
import com.etekisi.etekisi.repository.DriverLocationRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web Push: notifications on a phone or computer even when e-Tekisi isn't
 * open. Every in-app notification is also pushed to the person's subscribed
 * devices, and online drivers near a new request hear about it.
 *
 * <ul>
 *   <li>Messages are encrypted for each device (RFC 8291) and signed with the
 *       server's VAPID key (RFC 8292).</li>
 *   <li>Subscription endpoints must be on a known browser push service
 *       ({@code tekisi.push.allowed-hosts}): the server POSTs to them, so an
 *       arbitrary URL would let anyone make it call into other systems.</li>
 *   <li>Sent after the transaction commits, from a background thread. A
 *       device the push service says is gone (404/410) is forgotten.</li>
 * </ul>
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final int MAX_DEVICES_PER_ACCOUNT = 10;
    private static final int MAX_CONSECUTIVE_FAILURES = 10;
    private static final double NEARBY_KM = 10;

    public record Payload(String title, String body, String url, String tag) {}

    private final PushSubscriptionRepository subscriptions;
    private final VapidKeys vapid;
    private final ObjectProvider<BookingRepository> bookings;
    private final ObjectProvider<DriverRepository> drivers;
    private final ObjectProvider<DriverLocationRepository> locations;
    private final List<String> allowedHosts;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService sender = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "web-push");
        t.setDaemon(true);
        return t;
    });
    private final Counter sent;
    private final Counter failed;

    public PushService(PushSubscriptionRepository subscriptions, VapidKeys vapid,
                       ObjectProvider<BookingRepository> bookings, ObjectProvider<DriverRepository> drivers,
                       ObjectProvider<DriverLocationRepository> locations, ObjectProvider<MeterRegistry> registry,
                       @Value("${tekisi.push.allowed-hosts:fcm.googleapis.com,updates.push.services.mozilla.com,*.push.apple.com,*.notify.windows.com}")
                       List<String> allowedHosts) {
        this.subscriptions = subscriptions;
        this.vapid = vapid;
        this.bookings = bookings;
        this.drivers = drivers;
        this.locations = locations;
        this.allowedHosts = allowedHosts.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        MeterRegistry r = registry.getIfAvailable();
        this.sent = r == null ? null : Counter.builder("tekisi.push.sent").register(r);
        this.failed = r == null ? null : Counter.builder("tekisi.push.failed").register(r);
    }

    // ------------------------------------------------------- subscriptions --

    @Transactional
    public void subscribe(Role role, Long ownerId, String endpoint, String p256dh, String auth) {
        if (!vapid.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_DISABLED", "Notifications aren't set up on this server.", null);
        }
        requireAllowedEndpoint(endpoint);
        try {
            if (WebPushCrypto.b64(p256dh).length != 65 || WebPushCrypto.b64(auth).length != 16) throw new IllegalArgumentException();
            WebPushCrypto.publicKey(WebPushCrypto.b64(p256dh));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_SUBSCRIPTION", "That subscription's keys aren't valid.");
        }
        // The same browser signing in as someone else takes the subscription over.
        PushSubscription s = subscriptions.findByEndpoint(endpoint).orElseGet(PushSubscription::new);
        s.setOwnerRole(role);
        s.setOwnerId(ownerId);
        s.setEndpoint(endpoint);
        s.setP256dh(p256dh);
        s.setAuth(auth);
        s.setFailures(0);
        subscriptions.save(s);
        List<PushSubscription> mine = subscriptions.findByOwnerRoleAndOwnerId(role, ownerId);
        if (mine.size() > MAX_DEVICES_PER_ACCOUNT) {
            mine.stream().sorted(Comparator.comparing(PushSubscription::getCreatedAt))
                    .limit(mine.size() - MAX_DEVICES_PER_ACCOUNT).forEach(subscriptions::delete);
        }
    }

    @Transactional
    public void unsubscribe(Role role, Long ownerId, String endpoint) {
        subscriptions.deleteMine(endpoint, role, ownerId);
    }

    @Transactional
    public void forgetAll(Role role, Long ownerId) {
        subscriptions.deleteByOwner(role, ownerId);
    }

    public Map<String, Object> status(Role role, Long ownerId) {
        return Map.of("enabled", vapid.enabled(), "devices", subscriptions.countByOwnerRoleAndOwnerId(role, ownerId));
    }

    void requireAllowedEndpoint(String endpoint) {
        URI u;
        try {
            u = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_SUBSCRIPTION", "That isn't a push endpoint.");
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = allowedHosts.stream().anyMatch(a -> a.startsWith("*.") ? host.endsWith(a.substring(1)) : host.equals(a));
        // Push services are https; plain http only for a local test service.
        boolean secure = "https".equals(u.getScheme()) || ("http".equals(u.getScheme()) && host.equals("localhost"));
        if (!allowed || !secure) {
            throw ApiException.badRequest("INVALID_SUBSCRIPTION", "That isn't a known browser push service.");
        }
    }

    // ------------------------------------------------------------ sending --

    /** Pushes to every device of one account once the current transaction commits. */
    public void toUserAfterCommit(Role role, Long ownerId, Payload payload) {
        if (!vapid.enabled() || ownerId == null) return;
        afterCommit(() -> {
            for (PushSubscription s : subscriptions.findByOwnerRoleAndOwnerId(role, ownerId)) send(s, payload, Duration.ofHours(1));
        });
    }

    /** A new request: tell online, approved drivers within 10 km of the pick-up (when its position is known). */
    public void newRequestAfterCommit(Long bookingId) {
        if (!vapid.enabled() || bookingId == null) return;
        afterCommit(() -> {
            Booking b = bookings.getObject().findById(bookingId).orElse(null);
            if (b == null || b.getStatus() != BookingStatus.PENDING || b.getReleasedAt() == null || b.getPickupLat() == null) return;
            List<DriverLocation> recent = locations.getObject().findByLastUpdatedAfter(LocalDateTime.now().minusMinutes(10));
            Map<Long, DriverLocation> near = new HashMap<>();
            for (DriverLocation l : recent) {
                if (l.getLat() != null && l.getLng() != null && km(b.getPickupLat(), b.getPickupLng(), l.getLat(), l.getLng()) <= NEARBY_KM) {
                    near.put(l.getDriverId(), l);
                }
            }
            if (near.isEmpty()) return;
            List<Long> online = drivers.getObject().findAllById(near.keySet()).stream()
                    .filter(d -> d.isActive() && d.isApproved() && d.getDeletedAt() == null).map(Driver::getId).toList();
            if (online.isEmpty()) return;
            Payload p = new Payload("New trip request", b.getPickupLocation() + " → " + b.getDestination(), "/driver", "request-" + b.getId());
            for (PushSubscription s : subscriptions.findByOwnerRoleAndOwnerIdIn(Role.DRIVER, online)) {
                send(s, p, Duration.ofMinutes(5)); // stale after a few minutes
            }
        });
    }

    private void afterCommit(Runnable task) {
        Runnable async = () -> sender.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("push.task_failed error={}", e.getMessage());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    async.run();
                }
            });
        } else {
            async.run();
        }
    }

    void send(PushSubscription s, Payload payload, Duration ttl) {
        try {
            byte[] body = WebPushCrypto.encrypt(WebPushCrypto.b64(s.getP256dh()), WebPushCrypto.b64(s.getAuth()),
                    json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(s.getEndpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", vapid.authorization(s.getEndpoint()))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", String.valueOf(ttl.toSeconds()))
                    .header("Urgency", "high")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 404 || status == 410) {
                subscriptions.delete(s); // unsubscribed or expired on the device
                log.info("push.subscription_gone id={} status={}", s.getId(), status);
            } else if (status >= 200 && status < 300) {
                s.setFailures(0);
                s.setLastSuccessAt(Instant.now());
                subscriptions.save(s);
                if (sent != null) sent.increment();
            } else {
                fail(s, "status " + status);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            fail(s, e.getMessage());
        }
    }

    private void fail(PushSubscription s, String why) {
        if (failed != null) failed.increment();
        s.setFailures(s.getFailures() + 1);
        if (s.getFailures() >= MAX_CONSECUTIVE_FAILURES) {
            subscriptions.delete(s);
            log.warn("push.subscription_dropped id={} after {} failures, last: {}", s.getId(), s.getFailures(), why);
        } else {
            subscriptions.save(s);
            log.warn("push.send_failed id={} error={}", s.getId(), why);
        }
    }

    private static double km(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 6371 * Math.asin(Math.sqrt(h));
    }
}
