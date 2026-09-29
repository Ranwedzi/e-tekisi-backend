package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByCommuterId(Long commuterId);

    List<Booking> findByDriverIdAndStatus(Long driverId, String status);

    List<Booking> findByStatus(String status);

    List<Booking> findByDriverId(Long driverId);
}
