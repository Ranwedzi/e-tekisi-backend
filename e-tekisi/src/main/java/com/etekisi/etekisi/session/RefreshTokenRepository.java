package com.etekisi.etekisi.session;

import com.etekisi.etekisi.model.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** Locked, so two refreshes with the same token can't both rotate it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findForUpdate(@Param("hash") String hash);

    Optional<RefreshToken> findByTokenHash(String hash);

    /** The current (unused, live) token of each of the account's sessions. */
    @Query("""
            select t from RefreshToken t
            where t.ownerRole = :role and t.ownerId = :ownerId
              and t.usedAt is null and t.revokedAt is null and t.expiresAt > :now
            order by t.createdAt desc""")
    List<RefreshToken> findLive(@Param("role") Role role, @Param("ownerId") Long ownerId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.ownerRole = :role and t.ownerId = :ownerId and t.revokedAt is null")
    int revokeAll(@Param("role") Role role, @Param("ownerId") Long ownerId, @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
