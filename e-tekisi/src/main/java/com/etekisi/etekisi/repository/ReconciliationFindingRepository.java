package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.ReconciliationFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, Long> {

    List<ReconciliationFinding> findTop500ByRunIdOrderBySeverityAscIdAsc(Long runId);

    @Modifying
    @Query("delete from ReconciliationFinding f where f.runId in "
            + "(select r.id from ReconciliationRun r where r.startedAt < :cutoff)")
    int deleteForRunsStartedBefore(@Param("cutoff") Instant cutoff);
}
