package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    /** The latest finished run (any outcome but RUNNING). */
    Optional<ReconciliationRun> findFirstByStatusNotOrderByIdDesc(ReconciliationRun.Status status);

    List<ReconciliationRun> findTop30ByOrderByIdDesc();

    @Modifying
    @Query("delete from ReconciliationRun r where r.startedAt < :cutoff")
    int deleteStartedBefore(@Param("cutoff") Instant cutoff);
}
