package com.etekisi.etekisi.agent.trip;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TripGpsPingRepository extends JpaRepository<TripGpsPing, Long> {

    /** Pings within a window, ordered chronologically (oldest first). */
    List<TripGpsPing> findByBookingIdAndRecordedAtAfterOrderByRecordedAtAsc(Long bookingId, Instant since);

    /** Most-recent N pings (newest first) — caller can reverse if needed. */
    List<TripGpsPing> findByBookingIdOrderByRecordedAtDesc(Long bookingId, Pageable pageable);
}
