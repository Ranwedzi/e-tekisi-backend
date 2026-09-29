package com.etekisi.etekisi.agent.guardrail;

/**
 * Tiny haversine helper. Lives in guardrail because every distance the
 * platform cites must come from the same well-tested function — agents
 * must never invent or estimate distance.
 */
public final class Geo {
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private Geo() {}

    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }
}
