package com.etekisi.etekisi.agent.hotspot;

import com.etekisi.etekisi.agent.audit.DecisionAuditLogger;
import com.etekisi.etekisi.agent.dto.HotspotRecommendation;
import com.etekisi.etekisi.agent.dto.ReasonCode;
import com.etekisi.etekisi.agent.guardrail.*;
import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.QueueTicket;
import com.etekisi.etekisi.model.TaxiRank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Demand Hotspot service. NOT an LLM agent — it observes pending bookings
 * and queue depth per rank, then ranks ranks by *observed pressure*. We
 * never claim future demand here; UI text must say "expected" only when we
 * later add forecasting on top.
 */
@Service
public class HotspotService {

    public static final String NAME = "HotspotService";
    public static final String VERSION = "1.0.0-rules";

    private final TrustedDataService trusted;
    private final AgentProperties props;
    private final DecisionAuditLogger audit;

    // Phase 3.2 — optional time-series source. Field-injected to keep the
    // existing 3-arg constructor used by tests valid.
    @Autowired(required = false)
    private DemandPulseRepository pulseRepo;

    public HotspotService(TrustedDataService trusted,
                          AgentProperties props,
                          DecisionAuditLogger audit) {
        this.trusted = trusted;
        this.props = props;
        this.audit = audit;
    }

    public HotspotRecommendation recommend(String correlationId) {
        DecisionContext ctx = new DecisionContext(NAME, VERSION, correlationId);

        TrustedFact<List<TaxiRank>> ranksFact = trusted.getAllRanks();
        TrustedFact<List<Booking>> pendingFact = trusted.getPendingBookings();
        ctx.consulted(ranksFact).consulted(pendingFact);

        if (!ranksFact.isPresent() || ranksFact.value().isEmpty()) {
            ctx.addReason(ReasonCode.NO_DATA).markFallback().withConfidence(0.0);
            return publish(empty(ctx));
        }

        Map<String, Long> pendingByRank = new HashMap<>();
        for (Booking b : pendingFact.asOptional().orElse(List.of())) {
            if (b.getPickupLocation() == null) continue;
            pendingByRank.merge(b.getPickupLocation(), 1L, Long::sum);
        }

        List<HotspotRecommendation.Hotspot> spots = new ArrayList<>();
        Instant validUntil = Instant.now().plus(Duration.ofSeconds(props.getHotspot().getRecommendationTtlSeconds()));

        for (TaxiRank rank : ranksFact.value()) {
            int pending = pendingByRank.getOrDefault(rank.getName(), 0L).intValue();
            TrustedFact<List<QueueTicket>> qFact = trusted.getQueueAtRank(rank.getName());
            ctx.consulted(qFact);
            int drivers = qFact.asOptional().map(List::size).orElse(0);

            if (pending < props.getHotspot().getMinPendingForHotspot()) continue;

            // Phase 3.2 — recent15m = weighted average of the last 15 minute-buckets.
            // Falls back to the current pending count when no pulse history exists.
            int recent15m = computeRecent15m(rank.getName(), pending);
            double pressure = recent15m - 0.5 * drivers; // unitless, comparable
            spots.add(new HotspotRecommendation.Hotspot(
                    rank.getId(), rank.getName(),
                    pending, recent15m,
                    drivers, round(pressure), validUntil));
        }
        spots.sort(Comparator.comparingDouble(HotspotRecommendation.Hotspot::pressureScore).reversed());

        if (spots.isEmpty()) {
            ctx.addReason(ReasonCode.DEMAND_INSUFFICIENT_HISTORY).withConfidence(0.3);
        } else {
            ctx.addReason(ReasonCode.DEMAND_LIVE_PENDING).withConfidence(0.75);
        }
        HotspotRecommendation rec = new HotspotRecommendation(spots, ctx.seal());
        return publish(rec);
    }

    private HotspotRecommendation empty(DecisionContext ctx) {
        return new HotspotRecommendation(List.of(), ctx.seal());
    }

    private HotspotRecommendation publish(HotspotRecommendation r) {
        audit.record(r.metadata(), "spots=" + r.hotspots().size(), r);
        return r;
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }

    /**
     * Phase 3.2 — rolling weighted average over the last 15 minute buckets.
     * Newer buckets weighted higher (linear ramp). Falls back to the
     * current pending count when no pulse history exists — this keeps the
     * service degrading gracefully if the aggregator is paused.
     */
    private int computeRecent15m(String rankName, int currentPending) {
        if (pulseRepo == null) return currentPending;
        try {
            Instant since = Instant.now().minus(Duration.ofMinutes(15));
            List<DemandPulse> pulses = pulseRepo
                    .findByRankNameAndBucketMinuteGreaterThanEqualOrderByBucketMinuteAsc(rankName, since);
            if (pulses.isEmpty()) return currentPending;
            double weightedSum = 0.0;
            double weightTotal = 0.0;
            int n = pulses.size();
            for (int i = 0; i < n; i++) {
                double w = 1.0 + i; // 1 (oldest) … n (newest)
                weightedSum += pulses.get(i).getPendingCount() * w;
                weightTotal += w;
            }
            return (int) Math.round(weightedSum / weightTotal);
        } catch (RuntimeException repoFail) {
            return currentPending; // never block the recommendation path
        }
    }
}
