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

    /** Pending bookings this driver hasn't declined, newest first. */
    @Query("""
            select b from Booking b
            where b.status = com.etekisi.etekisi.model.BookingStatus.PENDING
              and b.id not in (select d.bookingId from BookingDecline d where d.driverId = :driverId)
            order by b.id desc
            """)
    List<Booking> findPendingNotDeclinedBy(@Param("driverId") Long driverId);
}
