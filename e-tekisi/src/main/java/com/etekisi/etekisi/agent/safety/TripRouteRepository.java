package com.etekisi.etekisi.agent.safety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TripRouteRepository extends JpaRepository<TripRoute, Long> {
    Optional<TripRoute> findByBookingId(Long bookingId);
}
