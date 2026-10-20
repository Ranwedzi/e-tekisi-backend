package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.DriverLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DriverLocationRepository extends JpaRepository<DriverLocation, Long> {
    // Find drivers who updated their GPS in the last 5 minutes
    List<DriverLocation> findByLastUpdatedAfter(LocalDateTime time);

    /** Retention: last-known positions of drivers who haven't pinged since the cutoff. */
    @Modifying
    @Query("delete from DriverLocation l where l.lastUpdated < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
