package com.etekisi.etekisi.agent.hotspot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DemandPulseRepository extends JpaRepository<DemandPulse, Long> {

    Optional<DemandPulse> findByRankNameAndBucketMinute(String rankName, Instant bucketMinute);

    /** All pulses for a rank since {@code since}, oldest first. */
    List<DemandPulse> findByRankNameAndBucketMinuteGreaterThanEqualOrderByBucketMinuteAsc(
            String rankName, Instant since);

    @Modifying
    @Transactional
    @Query("delete from DemandPulse d where d.bucketMinute < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
