package com.etekisi.etekisi.agent.eta;

import com.etekisi.etekisi.agent.dto.EtaEstimate;
import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import com.etekisi.etekisi.agent.guardrail.Geo;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Always-on heuristic ETA provider. Pure function of haversine distance and
 * a configured average minibus-taxi speed (incl. dwell). Never fails, never
 * throws, never invents.
 *
 * Bounded behaviour:
 *   - refuses (Optional.empty) when distance exceeds the configured cap
 *   - confidence falls off with distance (1km is far more trustable than 30km)
 *   - p90 is mean * 1.5 (explicit, not guessed) to absorb dwell + traffic
 */
@Component
public class HeuristicEtaProvider implements EtaProvider {

    public static final String PROVIDER_NAME = "heuristic";

    private final AgentProperties props;

    public HeuristicEtaProvider(AgentProperties props) {
        this.props = props;
    }

    @Override
    public String name() { return PROVIDER_NAME; }

    @Override
    public int priority() { return 0; } // lowest — last-resort fallback

    @Override
    public Optional<RouteQuote> quote(double originLat, double originLng,
                                      double destLat, double destLng) {
        double meters = Geo.haversineMeters(originLat, originLng, destLat, destLng);
        double km = meters / 1000.0;
        if (km > props.getEta().getMaxKm()) {
            return Optional.empty();
        }
        double speedKmh = props.getEta().getAvgSpeedKmh();
        if (speedKmh <= 0) return Optional.empty();
        int meanSeconds = (int) Math.round(km / speedKmh * 3600.0);
        int p90Seconds = (int) Math.round(meanSeconds * 1.5);
        // Linear confidence decay with distance, floored at 0.4 — we never
        // pretend a 30km haversine ETA is as trustable as a 1km one.
        double confidence = Math.max(0.4, 1.0 - km / props.getEta().getMaxKm());
        return Optional.of(new RouteQuote(
                meanSeconds, p90Seconds,
                round(km), round(confidence),
                EtaEstimate.Source.HEURISTIC, PROVIDER_NAME));
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
