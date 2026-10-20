package com.etekisi.etekisi.agent.idempotency;

import com.etekisi.etekisi.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
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
 *   <li>Keys are scoped to the caller: the controller passes a scope that
 *       includes the user id, so two users can't collide on a key.</li>
 *   <li>The key is <b>claimed first</b> (row inserted as IN_PROGRESS; the
 *       unique constraint lets only one request win), then the action runs,
 *       then the response is stored. So concurrent retries can never both
 *       run the side effect.</li>
 *   <li>A retry that arrives after completion gets the stored response. One
 *       that arrives while the first is still running gets 409.</li>
 *   <li>If the action fails, the claim is released so the client can retry.</li>
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
    private final TransactionTemplate newTx;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public IdempotencyService(IdempotencyKeyRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Execute {@code action} idempotently. When {@code key} is null/blank the
     * action is run unconditionally and the result is returned uncached.
     *
     * @param key    client-supplied Idempotency-Key header (may be null)
     * @param scope  caller + logical route, e.g. {@code u42:PUT /api/bookings/7/accept/42}
     * @param type   response type, used to rebuild a stored response
     * @param action the work to perform on first execution
     */
    public <T> T execute(String key, String scope, Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        if (key.length() > 128) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be at most 128 characters");
        }
        String route = scope.length() > 128 ? scope.substring(0, 128) : scope;

        Optional<T> replay = replayIfPresent(key, route, type);
        if (replay.isPresent()) return replay.get();

        IdempotencyKey claim;
        try {
            claim = newTx.execute(s -> {
                IdempotencyKey row = new IdempotencyKey();
                row.setIdempotencyKey(key);
                row.setRoute(route);
                row.setState(IdempotencyKey.IN_PROGRESS);
                row.setCreatedAt(Instant.now());
                row.setExpiresAt(Instant.now().plus(DEFAULT_TTL));
                return repo.saveAndFlush(row);
            });
        } catch (DataIntegrityViolationException race) {
            // Another request with this key got there first.
            log.info("idempotency.race key={} route={}", key, route);
            return replayIfPresent(key, route, type).orElseThrow(() -> inProgress());
        }

        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            release(claim);
            throw e;
        }

        try {
            newTx.executeWithoutResult(s -> {
                IdempotencyKey row = repo.findById(claim.getId()).orElse(claim);
                String body = encode(result);
                row.setResponseBody(body);
                row.setResponseHash(hash(body));
                row.setStatusCode(200);
                row.setState(IdempotencyKey.COMPLETED);
                repo.save(row);
            });
            log.debug("idempotency.stored key={} route={}", key, route);
        } catch (RuntimeException e) {
            // The side effect already happened; don't fail the request because
            // the receipt didn't persist. The claim stays IN_PROGRESS until TTL,
            // so a retry gets 409 rather than repeating the side effect.
            log.error("idempotency.persist.failed key={} route={} error={}", key, route, e.getMessage());
        }
        return result;
    }

    private <T> Optional<T> replayIfPresent(String key, String route, Class<T> type) {
        Optional<IdempotencyKey> existing = repo.findByIdempotencyKeyAndRoute(key, route);
        if (existing.isEmpty()) return Optional.empty();
        IdempotencyKey row = existing.get();
        if (!IdempotencyKey.COMPLETED.equals(row.getState())) {
            throw inProgress();
        }
        log.info("idempotency.hit key={} route={}", key, route);
        return Optional.ofNullable(decode(row.getResponseBody(), type));
    }

    private void release(IdempotencyKey claim) {
        try {
            newTx.executeWithoutResult(s -> repo.deleteById(claim.getId()));
        } catch (RuntimeException e) {
            log.warn("idempotency.release.failed id={} error={}", claim.getId(), e.getMessage());
        }
    }

    private static ApiException inProgress() {
        return ApiException.conflict("IDEMPOTENCY_IN_PROGRESS",
                "A request with this Idempotency-Key is still being processed. Retry shortly.");
    }

    @SchedulerLock(name = "idempotency-purge", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${tekisi.agent.idempotency.purge-cron:0 30 3 * * *}")
    public void purgeExpired() {
        try {
            int n = repo.deleteExpired(Instant.now());
            if (n > 0) log.info("idempotency.purged count={}", n);
        } catch (RuntimeException e) {
            log.error("idempotency.purge.failed error={}", e.getMessage());
        }
    }

    private <T> T decode(String body, Class<T> type) {
        if (body == null) return null;
        try {
            return json.readValue(body, type);
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
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
