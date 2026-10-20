package com.etekisi.etekisi.agent.insight;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.ArrayList;
import java.util.List;

public interface OperationalInsightRepository extends JpaRepository<OperationalInsight, Long>,
        JpaSpecificationExecutor<OperationalInsight> {

    List<OperationalInsight> findByBookingIdOrderByCreatedAtAsc(Long bookingId);

    List<OperationalInsight> findBySeverityOrderByCreatedAtDesc(
            OperationalInsight.Severity severity, Pageable pageable);

    long countByCodeAndCreatedAtAfter(InsightCode code, java.time.Instant after);

    /** Feeds the tekisi.safety.unacknowledged gauge. */
    long countBySeverityAndAcknowledgedAtIsNullAndCreatedAtAfter(
            OperationalInsight.Severity severity, java.time.Instant after);

    /**
     * Phase 3.7 — operator filtered feed. Each parameter is optional;
     * passing {@code null} means "do not filter on this field". Ordering
     * comes from {@code pageable} (the controller asks for newest first).
     *
     * <p>Built with a Specification so only the filters actually set reach
     * the SQL. The previous {@code (:since is null or …)} query failed on
     * PostgreSQL ("could not determine data type of parameter") whenever a
     * filter was left out.
     */
    default Page<OperationalInsight> findFiltered(OperationalInsight.Severity severity,
                                                  InsightCode code,
                                                  Long bookingId,
                                                  java.time.Instant since,
                                                  Pageable pageable) {
        return findFiltered(severity, code, bookingId, since, null, pageable);
    }

    /** As above; {@code acknowledged} true/false keeps only handled/unhandled rows. */
    default Page<OperationalInsight> findFiltered(OperationalInsight.Severity severity,
                                                  InsightCode code,
                                                  Long bookingId,
                                                  java.time.Instant since,
                                                  Boolean acknowledged,
                                                  Pageable pageable) {
        Specification<OperationalInsight> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (severity != null) where.add(cb.equal(root.get("severity"), severity));
            if (code != null) where.add(cb.equal(root.get("code"), code));
            if (bookingId != null) where.add(cb.equal(root.get("bookingId"), bookingId));
            if (since != null) where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), since));
            if (acknowledged != null) {
                where.add(acknowledged ? cb.isNotNull(root.get("acknowledgedAt")) : cb.isNull(root.get("acknowledgedAt")));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        return findAll(spec, pageable);
    }
}
