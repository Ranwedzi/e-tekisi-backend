package com.etekisi.etekisi.realtime;

import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.BookingRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live "something changed" events over Server-Sent Events.
 *
 * <p>Events carry only a topic ({@code bookings}, {@code pending},
 * {@code notifications}, {@code messages:42}, {@code queue}, {@code payouts},
 * {@code alerts}, {@code drivers}), never data: the page refetches what it
 * shows through the normal, authorised API. So a stream can't leak anything,
 * and polling stays as the fallback.
 *
 * <p>Events are sent after the transaction commits (so a refetch sees the
 * change), once per topic and recipient per transaction, from a background
 * thread (a slow phone can't hold up a payment). Publishing goes through a
 * {@link LiveEventRelay}: in-process by default; with Redis the relay fans
 * out to every backend instance.
 */
@Component
public class LiveEvents {

    private static final Logger log = LoggerFactory.getLogger(LiveEvents.class);
    public static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);
    static final String DRIVERS = "group:DRIVERS";
    static final String STAFF = "group:STAFF";
    private static final int MAX_STREAMS_PER_USER = 5;
    private static final Object PENDING_KEY = new Object();
    private static final String BOOKING_PREFIX = "booking:";

    private final Map<String, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "live-events");
        t.setDaemon(true);
        return t;
    });
    private final LiveEventRelay relay;
    private final ObjectProvider<BookingRepository> bookings;

    public LiveEvents(ObjectProvider<LiveEventRelay> relay, ObjectProvider<MeterRegistry> registry,
                      ObjectProvider<BookingRepository> bookings) {
        this.bookings = bookings;
        this.relay = relay.getIfAvailable(LiveEventRelay::local);
        this.relay.onMessage(this::deliverLocal);
        MeterRegistry r = registry.getIfAvailable();
        if (r != null) {
            Gauge.builder("tekisi.live.streams", streams, m -> m.values().stream().flatMap(Set::stream).distinct().count())
                    .description("Open live-event streams on this instance").register(r);
        }
    }

    // ------------------------------------------------------------ streams --

    public SseEmitter subscribe(Long userId, Role role) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        String user = userKey(role, userId);
        String group = role.isStaff() ? STAFF : role == Role.DRIVER ? DRIVERS : null;
        Set<SseEmitter> mine = streams.computeIfAbsent(user, k -> new CopyOnWriteArraySet<>());
        if (mine.size() >= MAX_STREAMS_PER_USER) {
            // Oldest tab loses its stream (it falls back to polling and reconnects).
            mine.stream().findFirst().ifPresent(old -> { mine.remove(old); old.complete(); });
        }
        mine.add(emitter);
        if (group != null) streams.computeIfAbsent(group, k -> new CopyOnWriteArraySet<>()).add(emitter);
        Runnable remove = () -> {
            mine.remove(emitter);
            if (group != null) Optional.ofNullable(streams.get(group)).ifPresent(s -> s.remove(emitter));
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> { remove.run(); emitter.complete(); });
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data("ok").reconnectTime(5_000));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    /** Keeps proxies and phones from dropping idle streams. */
    @Scheduled(fixedRate = 25_000)
    void heartbeat() {
        Set<SseEmitter> all = new HashSet<>();
        streams.values().forEach(all::addAll);
        for (SseEmitter e : all) {
            sender.execute(() -> {
                try {
                    e.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException ex) {
                    e.completeWithError(ex);
                }
            });
        }
    }

    // ---------------------------------------------------------- publishing --

    public void toUser(Role role, Long userId, String topic) {
        if (userId != null) publish(userKey(role, userId), topic);
    }

    /** The trip's commuter and driver, looked up after commit (not inside a Hibernate flush). */
    public void toBookingParticipants(Long bookingId, String topic) {
        if (bookingId != null) publish(BOOKING_PREFIX + bookingId, topic);
    }

    public void toDrivers(String topic) {
        publish(DRIVERS, topic);
    }

    public void toStaff(String topic) {
        publish(STAFF, topic);
    }

    private void publish(String key, String topic) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(key, topic);
            return;
        }
        @SuppressWarnings("unchecked")
        Set<String> pending = (Set<String>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (pending == null) {
            Set<String> batch = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(PENDING_KEY, batch);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                    if (status != STATUS_COMMITTED) return;
                    for (String e : batch) {
                        int i = e.indexOf('|');
                        send(e.substring(0, i), e.substring(i + 1));
                    }
                }
            });
            pending = batch;
        }
        pending.add(key + "|" + topic);
    }

    private void send(String key, String topic) {
        if (key.startsWith(BOOKING_PREFIX)) {
            BookingRepository repo = bookings.getIfAvailable();
            if (repo == null) return;
            try {
                repo.findById(Long.valueOf(key.substring(BOOKING_PREFIX.length()))).ifPresent(b -> {
                    relay.publish(userKey(Role.COMMUTER, b.getCommuterId()), topic);
                    if (b.getDriverId() != null) relay.publish(userKey(Role.DRIVER, b.getDriverId()), topic);
                });
            } catch (RuntimeException ex) {
                log.debug("live.booking_lookup_failed key={} error={}", key, ex.getMessage());
            }
            return;
        }
        relay.publish(key, topic);
    }

    /** Called by the relay on every instance; sends to the streams open here. */
    void deliverLocal(String key, String topic) {
        Set<SseEmitter> targets = streams.get(key);
        if (targets == null || targets.isEmpty()) return;
        for (SseEmitter e : targets) {
            sender.execute(() -> {
                try {
                    e.send(SseEmitter.event().name("change").data(topic));
                } catch (IOException | IllegalStateException ex) {
                    log.debug("live.send_failed key={} error={}", key, ex.getMessage());
                    e.completeWithError(ex);
                }
            });
        }
    }

    static String userKey(Role role, Long id) {
        return (role.isStaff() ? "STAFF" : role.name()) + ":" + id;
    }
}
