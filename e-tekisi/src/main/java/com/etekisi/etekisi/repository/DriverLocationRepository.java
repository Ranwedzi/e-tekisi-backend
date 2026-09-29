package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.DriverLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface DriverLocationRepository extends JpaRepository<DriverLocation, Long> {
    // Find drivers who updated their GPS in the last 5 minutes
    List<DriverLocation> findByLastUpdatedAfter(LocalDateTime time);
}