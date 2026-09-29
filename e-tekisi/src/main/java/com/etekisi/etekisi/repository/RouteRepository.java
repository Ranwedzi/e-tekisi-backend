package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RouteRepository extends JpaRepository<Route, Long> {
    // This finds a route by matching the start and end points
    Optional<Route> findByStartLocationIgnoreCaseAndDestinationIgnoreCase(String start, String destination);
}