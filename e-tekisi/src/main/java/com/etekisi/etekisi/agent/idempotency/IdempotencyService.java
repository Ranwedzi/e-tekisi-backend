package com.etekisi.etekisi.agent.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Idempotency service backed by {@code agent_schema.idempotency_keys}.
 *
 * <p>Contract:
 * <ul>
 *   <li>If no header is provided, the action runs normally and is NOT cached.
 *       (Idempotency is opt-in by the client.)</li>
 *   <li>If the header is provided and a stored receipt exists for (key, route),
 *       the cached body is returned and the action is NOT re-executed.</li>
 *   <li>If the header is provided and no receipt exists, the action runs and
 *       a receipt is persisted before returning. A concurrent retry that loses
 *       the unique-constraint race re-reads the now-existing receipt.</li>
 * </ul>
 *
 * <p>TTL defaults to 24h — the minimum window covering a typical mobile
 * network retry storm. A daily {@link #purgeExpired()} job evicts stale rows.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final IdempotencyKeyRepository repo;
    private final ObjectMapper json = new ObjectMapper();

    public IdempotencyService(IdempotencyKeyRepository repo) {
        this.repo = repo;
    }

    /**
     * Execute {@code action} idempotently. When {@code key} is null/blank the
     * action is run unconditionally and the result is returned uncached.
     *
     * @param key   client-supplied Idempotency-Key header (may be null)
     * @param route logical route identifier (e.g. {@code POST /api/bookings/create})
     * @param action the work to perform on first execution
     */
    public <T> T execute(String key, String route, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        Optional<IdempotencyKey> existing = repo.findByIdempotencyKeyAndRoute(key, route);
        if (existing.isPresent()) {
            log.info("idempotency.hit key={} route={}", key, route);
            return decode(existing.get().getResponseBody());
        }

        T result = action.get();
        try {
            IdempotencyKey row = new IdempotencyKey();
            row.setIdempotencyKey(key);
            row.setRoute(route);
            row.setResponseBody(encode(result));
            row.setResponseHash(hash(row.getResponseBody()));
            row.setStatusCode(200);
            row.setCreatedAt(Instant.now());
            row.setExpiresAt(Instant.now().plus(DEFAULT_TTL));
            repo.save(row);
            log.debug("idempotency.stored key={} route={}", key, route);
        } catch (DataIntegrityViolationException race) {
            // A concurrent retry won the race. Return the now-stored payload
            // so both responses agree.
            log.info("idempotency.race key={} route={} — returning stored response", key, route);
            return repo.findByIdempotencyKeyAndRoute(key, route)
                    .map(IdempotencyKey::getResponseBody)
                    .map(this::<T>decode)
                    .orElse(result);
        } catch (RuntimeException e) {
            // Don't poison the request just because the receipt failed to
            // persist; the side effect already happened. Log loudly.
            log.error("idempotency.persist.failed key={} route={} error={}", key, route, e.getMessage());
        }
        return result;
    }

    @Scheduled(cron = "${tekisi.agent.idempotency.purge-cron:0 30 3 * * *}")
    public void purgeExpired() {
        try {
            int n = repo.deleteExpired(Instant.now());
            if (n > 0) log.info("idempotency.purged count={}", n);
        } catch (RuntimeException e) {
            log.error("idempotency.purge.failed error={}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T decode(String body) {
        if (body == null) return null;
        try {
            // We cache the raw JSON; consumers (controllers) treat the cached
            // value as the response body verbatim. The Object cast is safe
            // because the controller layer hands us a serializable Map/DTO.
            return (T) json.readValue(body, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("idempotency.decode.failed error={}", e.getMessage());
            return null;
        }
    }

    private String encode(Object o) {
        if (o == null) return null;
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String hash(String s) {
        if (s == null) return "";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes());
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
