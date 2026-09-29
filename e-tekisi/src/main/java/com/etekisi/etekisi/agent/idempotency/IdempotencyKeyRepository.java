package com.etekisi.etekisi.agent.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByIdempotencyKeyAndRoute(String idempotencyKey, String route);

    @Modifying
    @Transactional
    @Query("delete from IdempotencyKey k where k.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
