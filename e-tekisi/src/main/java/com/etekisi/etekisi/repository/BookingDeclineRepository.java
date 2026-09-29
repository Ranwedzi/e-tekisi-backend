package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.BookingDecline;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BookingDeclineRepository extends JpaRepository<BookingDecline, Long> {
    Optional<BookingDecline> findByBookingIdAndDriverId(Long bookingId, Long driverId);
    List<BookingDecline> findByDriverId(Long driverId);
}
