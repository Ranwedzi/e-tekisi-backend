package com.etekisi.etekisi.agent.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface DecisionAuditRepository extends JpaRepository<DecisionAudit, Long> {

    /**
     * Bulk delete of audit rows older than the cutoff. Used by the retention
     * job (Phase 1.7). Returns the number of rows removed for observability.
     *
     * <p>Append-only invariant: this is the ONLY DELETE pathway permitted on
     * the table; the retention role is the only DB principal that should be
     * granted DELETE in production (see infra docs).
     */
    @Modifying
    @Transactional
    @Query("delete from DecisionAudit d where d.computedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
