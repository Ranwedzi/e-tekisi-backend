package com.etekisi.etekisi.cluster;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@link SharedState} in Redis, shared by every backend instance. Keys are
 * prefixed with {@code tekisi:}. Counters use small Lua scripts so the
 * increment and the expiry are one atomic step.
 */
public class RedisSharedState implements SharedState {

    private static final String PREFIX = "tekisi:";

    /** INCR, and set the expiry only when the key is new. */
    private static final DefaultRedisScript<Long> INCR_FIXED = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end return n",
            Long.class);
    /** INCR, and push the expiry back every time. */
    private static final DefaultRedisScript<Long> INCR_SLIDING = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) redis.call('PEXPIRE', KEYS[1], ARGV[1]) return n",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisSharedState(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long n = redis.execute(INCR_FIXED, List.of(PREFIX + key), String.valueOf(ttl.toMillis()));
        return n == null ? 0 : n;
    }

    @Override
    public long incrementSliding(String key, Duration ttl) {
        Long n = redis.execute(INCR_SLIDING, List.of(PREFIX + key), String.valueOf(ttl.toMillis()));
        return n == null ? 0 : n;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(PREFIX + key));
    }

    @Override
    public Duration ttl(String key) {
        Long ms = redis.getExpire(PREFIX + key, java.util.concurrent.TimeUnit.MILLISECONDS);
        return ms == null || ms <= 0 ? Duration.ZERO : Duration.ofMillis(ms);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(PREFIX + key, value, ttl);
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + key, value, ttl));
    }

    @Override
    public void delete(String key) {
        redis.delete(PREFIX + key);
    }

    @Override
    public boolean shared() {
        return true;
    }
}
