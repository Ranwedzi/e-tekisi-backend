package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.QueueTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QueueRepository extends JpaRepository<QueueTicket, Long> {

    // Drivers in line at a rank, first-in-first-out.
    List<QueueTicket> findByTaxiRankAndStatusOrderByJoinedAtAsc(String taxiRank, String status);

    List<QueueTicket> findByTaxiRankAndStatusInOrderByJoinedAtAsc(String taxiRank, Collection<String> statuses);

    // Stops a driver holding two live tickets.
    Optional<QueueTicket> findFirstByDriverIdAndStatusIn(Long driverId, Collection<String> statuses);

    long countByTaxiRankAndStatus(String taxiRank, String status);

    List<QueueTicket> findByDriverIdOrderByJoinedAtDesc(Long driverId);

    long deleteByDriverId(Long driverId);
}
