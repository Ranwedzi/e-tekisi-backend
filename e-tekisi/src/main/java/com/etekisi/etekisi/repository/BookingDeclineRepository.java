package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.BookingDecline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingDeclineRepository extends JpaRepository<BookingDecline, Long> {
    Optional<BookingDecline> findByBookingIdAndDriverId(Long bookingId, Long driverId);

    long countByDriverIdAndCreatedAtAfter(Long driverId, LocalDateTime since);

    /** Rows of [driverId, count] for the given drivers since {@code since}. */
    @Query("""
            select d.driverId, count(d) from BookingDecline d
            where d.driverId in :driverIds and d.createdAt > :since
            group by d.driverId
            """)
    List<Object[]> countByDriverIdsSince(@Param("driverIds") Collection<Long> driverIds,
                                         @Param("since") LocalDateTime since);
}
