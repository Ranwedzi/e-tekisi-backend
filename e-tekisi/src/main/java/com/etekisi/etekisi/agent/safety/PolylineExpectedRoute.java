package com.etekisi.etekisi.agent.safety;

import com.etekisi.etekisi.agent.guardrail.Geo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3.1 — real route-deviation. Computes minimum perpendicular
 * distance from a GPS point to the planned polyline using haversine
 * and a per-segment projection.
 *
 * <p>Bounded cost: O(N) per call where N = number of polyline points.
 * For SA city routes N is typically &lt; 200, giving sub-millisecond
 * per-evaluation cost on the safety hot path.
 *
 * <p>Anti-hallucination: the polyline is the source of truth; we never
 * "smooth" or interpolate. If the polyline is empty or malformed, the
 * factory returns {@link SafetyAgent.ExpectedRoute#ALWAYS_ON_PATH}.
 */
public final class PolylineExpectedRoute implements SafetyAgent.ExpectedRoute {

    private final double[] lats;
    private final double[] lngs;

    private PolylineExpectedRoute(double[] lats, double[] lngs) {
        this.lats = lats;
        this.lngs = lngs;
    }

    /**
     * Parse the {@code polyline_json} TEXT column ({@code [[lat,lng],...]})
     * into an evaluator. Returns the no-op evaluator on any parse failure
     * so the safety pipeline degrades gracefully rather than crashing.
     */
    public static SafetyAgent.ExpectedRoute fromJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH;
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isArray() || root.isEmpty()) return SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH;
            List<double[]> pts = new ArrayList<>(root.size());
            for (JsonNode pair : root) {
                if (!pair.isArray() || pair.size() < 2) continue;
                double lat = pair.get(0).asDouble(Double.NaN);
                double lng = pair.get(1).asDouble(Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                pts.add(new double[]{lat, lng});
            }
            if (pts.isEmpty()) return SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH;
            double[] lats = new double[pts.size()];
            double[] lngs = new double[pts.size()];
            for (int i = 0; i < pts.size(); i++) {
                lats[i] = pts.get(i)[0];
                lngs[i] = pts.get(i)[1];
            }
            return new PolylineExpectedRoute(lats, lngs);
        } catch (Exception e) {
            return SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH;
        }
    }

    @Override
    public double distanceFromPathMeters(double lat, double lng) {
        if (lats.length == 0) return 0.0;
        if (lats.length == 1) {
            return Geo.haversineMeters(lat, lng, lats[0], lngs[0]);
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < lats.length - 1; i++) {
            double d = pointToSegmentMeters(lat, lng, lats[i], lngs[i], lats[i + 1], lngs[i + 1]);
            if (d < best) best = d;
        }
        return best;
    }

    /**
     * Approximate point-to-segment distance via local equirectangular
     * projection. Accurate to a few meters for segments &lt; 1 km, which
     * is well within the safety threshold (default 750 m). We never need
     * sub-meter accuracy here — the deviation threshold is the contract.
     */
    private static double pointToSegmentMeters(double lat, double lng,
                                               double lat1, double lng1,
                                               double lat2, double lng2) {
        // Project to local meters using the segment midpoint as origin.
        double midLat = (lat1 + lat2) / 2.0;
        double cosLat = Math.cos(Math.toRadians(midLat));
        double mPerDegLat = 111_320.0;
        double mPerDegLng = 111_320.0 * cosLat;

        double px = (lng - lng1) * mPerDegLng;
        double py = (lat - lat1) * mPerDegLat;
        double sx = (lng2 - lng1) * mPerDegLng;
        double sy = (lat2 - lat1) * mPerDegLat;

        double seg2 = sx * sx + sy * sy;
        double t = seg2 == 0.0 ? 0.0 : (px * sx + py * sy) / seg2;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;

        double cx = sx * t;
        double cy = sy * t;
        double dx = px - cx;
        double dy = py - cy;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** For tests and diagnostics. */
    public int pointCount() { return lats.length; }
}
