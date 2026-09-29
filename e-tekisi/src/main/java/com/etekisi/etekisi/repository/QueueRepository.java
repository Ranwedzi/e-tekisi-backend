package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.QueueTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface QueueRepository extends JpaRepository<QueueTicket, Long> {

    // This finds all drivers currently in line at a specific rank,
    // sorted by the time they joined (First-In-First-Out).
    List<QueueTicket> findByTaxiRankAndStatusOrderByJoinedAtAsc(String taxiRank, String status);

    // This helps us check if a driver is already in a queue so they don't join twice.
    Optional<QueueTicket> findByDriverIdAndStatus(Long driverId, String status);

    long countByTaxiRankAndStatus(String taxiRank, String status);

}
