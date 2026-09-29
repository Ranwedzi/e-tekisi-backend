package com.etekisi.etekisi.agent.guardrail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralised tunables for the agent layer. Default values are conservative;
 * production tuning should happen via application.properties, never via code.
 */
@Configuration
@ConfigurationProperties(prefix = "tekisi.agent")
public class AgentProperties {

    private final Dispatch dispatch = new Dispatch();
    private final Eta eta = new Eta();
    private final Safety safety = new Safety();
    private final Hotspot hotspot = new Hotspot();
    private final Intent intent = new Intent();
    private final Throttle throttle = new Throttle();
    private final Insight insight = new Insight();

    public Dispatch getDispatch() { return dispatch; }
    public Eta getEta() { return eta; }
    public Safety getSafety() { return safety; }
    public Hotspot getHotspot() { return hotspot; }
    public Intent getIntent() { return intent; }
    public Throttle getThrottle() { return throttle; }
    public Insight getInsight() { return insight; }

    public static class Dispatch {
        /** Driver candidates beyond this radius are excluded. */
        private double maxRadiusMeters = 5_000;
        /** Below this score the engine returns NO_MATCH instead of assigning. */
        private double minActionableScore = 0.45;
        /** Below this confidence the decision is marked LOW and gated. */
        private double minActionableConfidence = 0.55;

        public double getMaxRadiusMeters() { return maxRadiusMeters; }
        public void setMaxRadiusMeters(double v) { this.maxRadiusMeters = v; }
        public double getMinActionableScore() { return minActionableScore; }
        public void setMinActionableScore(double v) { this.minActionableScore = v; }
        public double getMinActionableConfidence() { return minActionableConfidence; }
        public void setMinActionableConfidence(double v) { this.minActionableConfidence = v; }
    }

    public static class Eta {
        /** Used when no model is loaded — minibus avg incl. dwell. */
        private double avgSpeedKmh = 28.0;
        /** Above this distance the heuristic refuses to estimate. */
        private double maxKm = 80.0;

        public double getAvgSpeedKmh() { return avgSpeedKmh; }
        public void setAvgSpeedKmh(double v) { this.avgSpeedKmh = v; }
        public double getMaxKm() { return maxKm; }
        public void setMaxKm(double v) { this.maxKm = v; }
    }

    public static class Safety {
        private double deviationThresholdMeters = 750;
        private long stationarySeconds = 180;
        private int minPingsRequired = 4;

        public double getDeviationThresholdMeters() { return deviationThresholdMeters; }
        public void setDeviationThresholdMeters(double v) { this.deviationThresholdMeters = v; }
        public long getStationarySeconds() { return stationarySeconds; }
        public void setStationarySeconds(long v) { this.stationarySeconds = v; }
        public int getMinPingsRequired() { return minPingsRequired; }
        public void setMinPingsRequired(int v) { this.minPingsRequired = v; }
    }

    public static class Hotspot {
        private int recommendationTtlSeconds = 300;
        private int minPendingForHotspot = 2;

        public int getRecommendationTtlSeconds() { return recommendationTtlSeconds; }
        public void setRecommendationTtlSeconds(int v) { this.recommendationTtlSeconds = v; }
        public int getMinPendingForHotspot() { return minPendingForHotspot; }
        public void setMinPendingForHotspot(int v) { this.minPendingForHotspot = v; }
    }

    public static class Intent {
        /** When false, only deterministic fuzzy matcher runs (LLM disabled). */
        private boolean llmEnabled = false;
        private double minMatchScore = 0.65;

        public boolean isLlmEnabled() { return llmEnabled; }
        public void setLlmEnabled(boolean v) { this.llmEnabled = v; }
        public double getMinMatchScore() { return minMatchScore; }
        public void setMinMatchScore(double v) { this.minMatchScore = v; }
    }

    /**
     * Hot-path throttling for driver-location event consumption.
     * Applied to safety re-evaluation only — never to GPS persistence.
     */
    public static class Throttle {
        /** Minimum seconds between safety evaluations per driver. 0 disables throttling. */
        private long locationMinIntervalSeconds = 5;
        /** Minimum movement (m) before another safety evaluation runs for a driver. 0 disables. */
        private double locationMinMovementMeters = 25.0;

        public long getLocationMinIntervalSeconds() { return locationMinIntervalSeconds; }
        public void setLocationMinIntervalSeconds(long v) { this.locationMinIntervalSeconds = v; }
        public double getLocationMinMovementMeters() { return locationMinMovementMeters; }
        public void setLocationMinMovementMeters(double v) { this.locationMinMovementMeters = v; }
    }

    /** Tunables for the operator-side TripAnomalyAgent. */
    public static class Insight {
        /** Fare (rand) above which a trip is flagged as a high-fare anomaly for review. */
        private double highFareRand = 400.0;
        /** Minimum number of recorded GPS pings expected for a healthy completed trip. */
        private int minPingsForTrip = 3;

        public double getHighFareRand() { return highFareRand; }
        public void setHighFareRand(double v) { this.highFareRand = v; }
        public int getMinPingsForTrip() { return minPingsForTrip; }
        public void setMinPingsForTrip(int v) { this.minPingsForTrip = v; }
    }
}
