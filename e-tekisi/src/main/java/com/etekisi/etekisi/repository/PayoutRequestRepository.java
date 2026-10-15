package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.PayoutRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PayoutRequestRepository extends JpaRepository<PayoutRequest, Long> {
    List<PayoutRequest> findByDriverIdOrderByCreatedAtDesc(Long driverId);

    long countByDriverIdAndCreatedAtAfter(Long driverId, Instant since);

    /** Oldest first: the order operators should work through them. */
    List<PayoutRequest> findTop200ByStatusOrderByCreatedAtAsc(String status);

    List<PayoutRequest> findTop200ByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PayoutRequest p where p.id = :id")
    Optional<PayoutRequest> findByIdForUpdate(@Param("id") Long id);
}
