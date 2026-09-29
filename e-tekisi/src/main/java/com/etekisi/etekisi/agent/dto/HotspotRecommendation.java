package com.etekisi.etekisi.agent.dto;

import java.time.Instant;
import java.util.List;

public record HotspotRecommendation(
        List<Hotspot> hotspots,
        DecisionMetadata metadata
) {
    public record Hotspot(
            Long rankId,
            String rankName,
            int pendingBookings,        // observed, not predicted
            int recentBookings15m,      // observed window
            int driversInQueue,
            double pressureScore,       // unitless, comparable across ranks
            Instant validUntil
    ) {}
}
