package com.etekisi.etekisi.agent.insight;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OperationalInsightRepository extends JpaRepository<OperationalInsight, Long> {

    List<OperationalInsight> findByBookingIdOrderByCreatedAtAsc(Long bookingId);

    List<OperationalInsight> findBySeverityOrderByCreatedAtDesc(
            OperationalInsight.Severity severity, Pageable pageable);

    long countByCodeAndCreatedAtAfter(InsightCode code, java.time.Instant after);

    /**
     * Phase 3.7 — operator filtered feed. Each parameter is optional;
     * passing {@code null} means "do not filter on this field". Results are
     * always ordered most-recent-first.
     */
    @Query("""
           select i from OperationalInsight i
           where (:severity is null or i.severity = :severity)
             and (:code is null or i.code = :code)
             and (:bookingId is null or i.bookingId = :bookingId)
             and (:since is null or i.createdAt >= :since)
           order by i.createdAt desc
           """)
    Page<OperationalInsight> findFiltered(
            @Param("severity") OperationalInsight.Severity severity,
            @Param("code") InsightCode code,
            @Param("bookingId") Long bookingId,
            @Param("since") java.time.Instant since,
            Pageable pageable);
}
