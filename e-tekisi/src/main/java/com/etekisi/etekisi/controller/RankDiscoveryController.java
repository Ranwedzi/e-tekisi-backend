package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.TaxiRank;
import com.etekisi.etekisi.repository.QueueRepository;
import com.etekisi.etekisi.repository.RankRepository;
import com.etekisi.etekisi.service.OsmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/ranks")
public class RankDiscoveryController {
    private static final int DEFAULT_RADIUS_METERS = 8000;
    private static final int MAX_RADIUS_METERS = 20000;
    private static final int MAX_RESULTS = 50;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RankRepository rankRepository;
    private final QueueRepository queueRepository;
    private final OsmGateway osm;

    public RankDiscoveryController(RankRepository rankRepository, QueueRepository queueRepository, OsmGateway osm) {
        this.rankRepository = rankRepository;
        this.queueRepository = queueRepository;
        this.osm = osm;
    }

    @GetMapping("/nearby")
    public ResponseEntity<Map<String, Object>> getNearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(required = false) Integer radius
    ) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID_COORDINATES", "ranks", List.of()));
        }
        int radiusMeters = Math.min(Math.max(radius != null ? radius : DEFAULT_RADIUS_METERS, 500), MAX_RADIUS_METERS);

        try {
            // Locale.ROOT: in en-ZA "%f" would print a decimal comma and break the query.
            String overpassQuery = String.format(Locale.ROOT, """
                [out:json][timeout:8];
                (
                  node["amenity"="taxi"](around:%d,%f,%f);
                  node["public_transport"="station"]["station"="taxi"](around:%d,%f,%f);
                  way["amenity"="taxi"](around:%d,%f,%f);
                  relation["amenity"="taxi"](around:%d,%f,%f);
                );
                out center tags;
                """,
                    radiusMeters, lat, lng,
                    radiusMeters, lat, lng,
                    radiusMeters, lat, lng,
                    radiusMeters, lat, lng
            );

            // Overpass results are cached for a minute per ~1 km cell; queue sizes are always live.
            JsonNode root = objectMapper.readTree(osm.overpass(cacheKey(lat, lng, radiusMeters), overpassQuery));
            JsonNode elements = root.path("elements");
            List<Map<String, Object>> ranks = new ArrayList<>();
            Set<String> seenKeys = new HashSet<>();

            if (elements.isArray()) {
                for (JsonNode el : elements) {
                    double rLat = el.has("lat") ? el.get("lat").asDouble(Double.NaN) : el.path("center").path("lat").asDouble(Double.NaN);
                    double rLng = el.has("lon") ? el.get("lon").asDouble(Double.NaN) : el.path("center").path("lon").asDouble(Double.NaN);
                    if (!Double.isFinite(rLat) || !Double.isFinite(rLng)) continue;

                    String name = el.path("tags").path("name").asText("");
                    if (name.isBlank()) name = "Taxi Rank";

                    String key = "osm:" + el.path("type").asText("node") + ":" + el.path("id").asText();
                    if (seenKeys.contains(key)) continue;
                    seenKeys.add(key);

                    Map<String, Object> item = new HashMap<>();
                    item.put("key", key);
                    item.put("name", name);
                    item.put("lat", rLat);
                    item.put("lng", rLng);
                    item.put("source", "OSM");
                    item.put("distanceKm", round2(haversineKm(lat, lng, rLat, rLng)));
                    item.put("queueSize", queueRepository.countByTaxiRankAndStatus(key, "WAITING"));
                    ranks.add(item);
                    if (ranks.size() >= MAX_RESULTS) break;
                }
            }

            // Merge in DB ranks as backup/verified entries
            for (TaxiRank rank : rankRepository.findAll()) {
                String key = "db:" + rank.getId();
                if (seenKeys.contains(key)) continue;
                Map<String, Object> item = new HashMap<>();
                item.put("key", key);
                item.put("name", rank.getName());
                item.put("lat", null);
                item.put("lng", null);
                item.put("source", "DB");
                item.put("distanceKm", null);
                item.put("queueSize", queueRepository.countByTaxiRankAndStatus(key, "WAITING"));
                ranks.add(item);
            }

            ranks.sort(Comparator.comparing(r -> {
                Object d = r.get("distanceKm");
                return d instanceof Number ? ((Number) d).doubleValue() : Double.MAX_VALUE;
            }));

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "OK");
            payload.put("ranks", ranks);
            payload.put("suggestedKey", suggestRankKey(ranks));
            return ResponseEntity.ok(payload);
        } catch (OsmGateway.UpstreamException | java.io.IOException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("status", "ERROR", "ranks", List.of()));
        }
    }

    private String suggestRankKey(List<Map<String, Object>> ranks) {
        String bestKey = "";
        double bestScore = Double.MAX_VALUE;
        for (Map<String, Object> rank : ranks) {
            String key = String.valueOf(rank.get("key"));
            double distance = rank.get("distanceKm") instanceof Number ? ((Number) rank.get("distanceKm")).doubleValue() : 25.0;
            int queueSize = rank.get("queueSize") instanceof Number ? ((Number) rank.get("queueSize")).intValue() : 0;
            double score = (queueSize * 2.0) + distance;
            if (score < bestScore) {
                bestScore = score;
                bestKey = key;
            }
        }
        return bestKey;
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371.0 * c;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String cacheKey(double lat, double lng, int radius) {
        double latKey = Math.round(lat * 100.0) / 100.0;
        double lngKey = Math.round(lng * 100.0) / 100.0;
        return latKey + ":" + lngKey + ":" + radius;
    }
}
