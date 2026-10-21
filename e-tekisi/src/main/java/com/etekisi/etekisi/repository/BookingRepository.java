package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByCommuterIdOrderByIdDesc(Long commuterId);

    List<Booking> findByDriverIdAndStatus(Long driverId, BookingStatus status);

    List<Booking> findByStatus(BookingStatus status);

    List<Booking> findByDriverIdOrderByIdDesc(Long driverId);

    /** Row lock for every state transition, so two drivers can't accept the same booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    /** Offers whose time is up; the expiry job returns them to PENDING. */
    List<Booking> findByStatusAndOfferExpiresAtBefore(BookingStatus status, java.time.Instant cutoff);

    /**
     * Open requests this driver hasn't declined, newest first: released
     * (scheduled trips only show shortly before departure) and not expired.
     */
    @Query("""
            select b from Booking b
            where b.status = com.etekisi.etekisi.model.BookingStatus.PENDING
              and b.releasedAt is not null
              and (b.requestExpiresAt is null or b.requestExpiresAt > :now)
              and b.id not in (select d.bookingId from BookingDecline d where d.driverId = :driverId)
            order by b.id desc
            """)
    List<Booking> findPendingNotDeclinedBy(@Param("driverId") Long driverId, @Param("now") java.time.Instant now);

    /** Scheduled trips due to be shown to drivers. */
    List<Booking> findByStatusAndReleasedAtIsNullAndScheduledAtBefore(BookingStatus status, java.time.Instant cutoff);

    /** Requests nobody took in time. */
    List<Booking> findByStatusAndRequestExpiresAtBefore(BookingStatus status, java.time.Instant cutoff);
}
