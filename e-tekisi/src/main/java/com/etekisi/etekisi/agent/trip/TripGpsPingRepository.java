package com.etekisi.etekisi.agent.trip;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.etekisi.etekisi.agent.insight.OperationalInsight;

import java.time.Instant;
import java.util.List;

public interface TripGpsPingRepository extends JpaRepository<TripGpsPing, Long> {

    /** Pings within a window, ordered chronologically (oldest first). */
    List<TripGpsPing> findByBookingIdAndRecordedAtAfterOrderByRecordedAtAsc(Long bookingId, Instant since);

    /** Most-recent N pings (newest first) — caller can reverse if needed. */
    List<TripGpsPing> findByBookingIdOrderByRecordedAtDesc(Long bookingId, Pageable pageable);

    /**
     * Retention: pings older than the cutoff, except for trips with a CRITICAL
     * insight (panic, route deviation, etc.), which are kept as evidence.
     */
    @Modifying
    @Query("""
            delete from TripGpsPing p where p.recordedAt < :cutoff
            and p.bookingId not in (
                select i.bookingId from com.etekisi.etekisi.agent.insight.OperationalInsight i
                where i.bookingId is not null and i.severity = :keep)""")
    int deleteOlderThanExcept(@Param("cutoff") Instant cutoff,
                              @Param("keep") OperationalInsight.Severity keep);

    default int deleteOlderThanExceptFlagged(Instant cutoff) {
        return deleteOlderThanExcept(cutoff, OperationalInsight.Severity.CRITICAL);
    }
}
