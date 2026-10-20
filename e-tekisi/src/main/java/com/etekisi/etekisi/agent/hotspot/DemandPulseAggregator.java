package com.etekisi.etekisi.agent.hotspot;

import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.QueueTicket;
import com.etekisi.etekisi.model.TaxiRank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3.2 — populates {@code agent_schema.demand_pulse} once per minute.
 *
 * <p>For every known rank, captures (pending bookings count, queue depth)
 * keyed by the current minute. Idempotent: if the row for (rank, minute)
 * exists, it is updated rather than duplicated (the unique constraint is
 * the contract).
 *
 * <p>Cleanup: rolls off rows older than 7 days at 03:45 daily — same window
 * as decision_audit retention; tunable via
 * {@code tekisi.agent.hotspot.pulse-retention-days}.
 */
@Component
public class DemandPulseAggregator {

    private static final Logger log = LoggerFactory.getLogger(DemandPulseAggregator.class);

    private final DemandPulseRepository repo;
    private final TrustedDataService trusted;

    public DemandPulseAggregator(DemandPulseRepository repo, TrustedDataService trusted) {
        this.repo = repo;
        this.trusted = trusted;
    }

    @SchedulerLock(name = "demand-pulse", lockAtMostFor = "PT50S", lockAtLeastFor = "PT30S")
    @Scheduled(fixedRateString = "${tekisi.agent.hotspot.pulse-interval-ms:60000}",
               initialDelayString = "${tekisi.agent.hotspot.pulse-initial-delay-ms:30000}")
    public void aggregate() {
        try {
            TrustedFact<List<TaxiRank>> ranks = trusted.getAllRanks();
            if (!ranks.isPresent() || ranks.value().isEmpty()) return;

            TrustedFact<List<Booking>> pending = trusted.getPendingBookings();
            Map<String, Integer> pendingByRank = new HashMap<>();
            for (Booking b : pending.asOptional().orElse(List.of())) {
                if (b.getPickupLocation() == null) continue;
                pendingByRank.merge(b.getPickupLocation(), 1, Integer::sum);
            }

            Instant bucket = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            for (TaxiRank rank : ranks.value()) {
                int p = pendingByRank.getOrDefault(rank.getName(), 0);
                TrustedFact<List<QueueTicket>> q = trusted.getQueueAtRank(rank.getName());
                int qd = q.asOptional().map(List::size).orElse(0);

                DemandPulse row = repo.findByRankNameAndBucketMinute(rank.getName(), bucket)
                        .orElseGet(() -> {
                            DemandPulse fresh = new DemandPulse();
                            fresh.setRankName(rank.getName());
                            fresh.setBucketMinute(bucket);
                            fresh.setCreatedAt(Instant.now());
                            return fresh;
                        });
                row.setPendingCount(p);
                row.setQueueDepth(qd);
                repo.save(row);
            }
        } catch (RuntimeException e) {
            log.warn("agent.hotspot.pulse.aggregate_failed error={}", e.getMessage());
        }
    }

    @SchedulerLock(name = "demand-pulse-purge", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${tekisi.agent.hotspot.pulse-purge-cron:0 45 3 * * *}")
    public void purge() {
        try {
            int n = repo.deleteOlderThan(Instant.now().minus(Duration.ofDays(7)));
            if (n > 0) log.info("agent.hotspot.pulse.purged count={}", n);
        } catch (RuntimeException e) {
            log.warn("agent.hotspot.pulse.purge_failed error={}", e.getMessage());
        }
    }
}
