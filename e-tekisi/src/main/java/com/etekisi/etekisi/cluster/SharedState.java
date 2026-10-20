package com.etekisi.etekisi.cluster;

import java.time.Duration;
import java.util.Optional;

/**
 * Small key-value store with expiry for state that every backend instance
 * must agree on: login-throttle counters, MFA sign-in challenges, the
 * map-service request slots and cache. {@link MemorySharedState} (default)
 * is right for one instance; {@link RedisSharedState} (REDIS_ENABLED=true)
 * makes it shared.
 */
public interface SharedState {

    /** Adds one and returns the new count; the key expires {@code ttl} after it was first created. */
    long increment(String key, Duration ttl);

    /** Adds one and returns the new count; every increment pushes the expiry back to {@code ttl} from now. */
    long incrementSliding(String key, Duration ttl);

    Optional<String> get(String key);

    /** Time left before {@code key} expires, or zero if it doesn't exist. */
    Duration ttl(String key);

    void put(String key, String value, Duration ttl);

    /** Sets it only if it doesn't exist yet; true if this call set it. */
    boolean putIfAbsent(String key, String value, Duration ttl);

    void delete(String key);

    /** True when the state is shared across instances. */
    boolean shared();
}
