package com.etekisi.etekisi.push;

import com.etekisi.etekisi.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByOwnerRoleAndOwnerId(Role role, Long ownerId);

    List<PushSubscription> findByOwnerRoleAndOwnerIdIn(Role role, Collection<Long> ownerIds);

    long countByOwnerRoleAndOwnerId(Role role, Long ownerId);

    @Modifying
    @Query("delete from PushSubscription s where s.ownerRole = :role and s.ownerId = :ownerId")
    int deleteByOwner(@Param("role") Role role, @Param("ownerId") Long ownerId);

    @Modifying
    @Query("delete from PushSubscription s where s.endpoint = :endpoint and s.ownerRole = :role and s.ownerId = :ownerId")
    int deleteMine(@Param("endpoint") String endpoint, @Param("role") Role role, @Param("ownerId") Long ownerId);
}
