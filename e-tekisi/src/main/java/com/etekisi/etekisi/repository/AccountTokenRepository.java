package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.AccountToken;
import com.etekisi.etekisi.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    Optional<AccountToken> findFirstBySecretHashAndChannel(String secretHash, AccountToken.Channel channel);

    /** The newest SMS code of this kind that can still be used. */
    Optional<AccountToken> findFirstByOwnerIdAndOwnerRoleAndPurposeAndChannelAndUsedAtIsNullAndExpiresAtAfterOrderByIdDesc(
            Long ownerId, Role ownerRole, AccountToken.Purpose purpose, AccountToken.Channel channel, Instant now);

    /** For send limits: what was sent recently. */
    List<AccountToken> findByOwnerIdAndOwnerRoleAndPurposeAndCreatedAtAfterOrderByCreatedAtDesc(
            Long ownerId, Role ownerRole, AccountToken.Purpose purpose, Instant after);

    /** Once one secret of a kind is used, the others of that kind stop working. */
    @Modifying
    @Query("""
            update AccountToken t set t.usedAt = :now
            where t.ownerId = :ownerId and t.ownerRole = :role and t.purpose = :purpose and t.usedAt is null""")
    int invalidate(@Param("ownerId") Long ownerId, @Param("role") Role role,
                   @Param("purpose") AccountToken.Purpose purpose, @Param("now") Instant now);

    @Modifying
    @Query("delete from AccountToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
