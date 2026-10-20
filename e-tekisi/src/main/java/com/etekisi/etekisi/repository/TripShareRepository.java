package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.TripShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TripShareRepository extends JpaRepository<TripShare, Long> {

    Optional<TripShare> findByTokenHash(String tokenHash);

    List<TripShare> findByBookingIdAndRevokedAtIsNull(Long bookingId);

    @Modifying
    @Query("delete from TripShare s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
