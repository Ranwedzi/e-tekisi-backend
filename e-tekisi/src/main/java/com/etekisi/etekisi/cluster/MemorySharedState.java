package com.etekisi.etekisi.cluster;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-process {@link SharedState}: correct for a single backend instance. */
public class MemorySharedState implements SharedState {

    private record Entry(String value, long expiresAtMillis) {
        boolean live(long now) {
            return expiresAtMillis > now;
        }
    }

    private final Map<String, Entry> map = new ConcurrentHashMap<>();

    @Override
    public long increment(String key, Duration ttl) {
        long now = System.currentTimeMillis();
        Entry e = map.compute(key, (k, old) -> old == null || !old.live(now)
                ? new Entry("1", now + ttl.toMillis())
                : new Entry(String.valueOf(Long.parseLong(old.value()) + 1), old.expiresAtMillis()));
        return Long.parseLong(e.value());
    }

    @Override
    public long incrementSliding(String key, Duration ttl) {
        long now = System.currentTimeMillis();
        Entry e = map.compute(key, (k, old) -> new Entry(
                String.valueOf(old == null || !old.live(now) ? 1 : Long.parseLong(old.value()) + 1), now + ttl.toMillis()));
        return Long.parseLong(e.value());
    }

    @Override
    public Optional<String> get(String key) {
        Entry e = map.get(key);
        return e != null && e.live(System.currentTimeMillis()) ? Optional.of(e.value()) : Optional.empty();
    }

    @Override
    public Duration ttl(String key) {
        Entry e = map.get(key);
        long left = e == null ? 0 : e.expiresAtMillis() - System.currentTimeMillis();
        return left > 0 ? Duration.ofMillis(left) : Duration.ZERO;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        map.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        long now = System.currentTimeMillis();
        boolean[] set = {false};
        map.compute(key, (k, old) -> {
            if (old != null && old.live(now)) return old;
            set[0] = true;
            return new Entry(value, now + ttl.toMillis());
        });
        return set[0];
    }

    @Override
    public void delete(String key) {
        map.remove(key);
    }

    @Override
    public boolean shared() {
        return false;
    }

    /** Drops expired entries (this instance's memory only, so it runs everywhere). */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> !e.getValue().live(now));
    }
}
