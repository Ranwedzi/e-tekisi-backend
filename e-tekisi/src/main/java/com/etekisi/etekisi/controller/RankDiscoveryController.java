package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.TaxiRank;
import com.etekisi.etekisi.repository.QueueRepository;
import com.etekisi.etekisi.repository.RankRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/ranks")
public class RankDiscoveryController {
    private static final int DEFAULT_RADIUS_METERS = 8000;
    private static final int MAX_RESULTS = 50;
    private static final long CACHE_TTL_MS = 60_000;
    private static final long MIN_FETCH_INTERVAL_MS = 10_000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private RankRepository rankRepository;
    @Autowired private QueueRepository queueRepository;

    private static class CacheEntry {
        private final long createdAt;
        private final long fetchedAt;
        private final Map<String, Object> payload;

        CacheEntry(long createdAt, long fetchedAt, Map<String, Object> payload) {
            this.createdAt = createdAt;
            this.fetchedAt = fetchedAt;
            this.payload = payload;
        }
    }

    private final Map<String, CacheEntry> cache = new HashMap<>();

    @GetMapping("/nearby")
    public ResponseEntity<Map<String, Object>> getNearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(required = false) Integer radius
    ) {
        int radiusMeters = radius != null ? radius : DEFAULT_RADIUS_METERS;
        String cacheKey = cacheKey(lat, lng, radiusMeters);
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && now - cached.createdAt < CACHE_TTL_MS) {
            return ResponseEntity.ok(cached.payload);
        }
        if (cached != null && now - cached.fetchedAt < MIN_FETCH_INTERVAL_MS) {
            return ResponseEntity.ok(cached.payload);
        }

        try {
            String overpassQuery = """
                [out:json][timeout:8];
                (
                  node["amenity"="taxi"](around:%d,%f,%f);
                  node["public_transport"="station"]["station"="taxi"](around:%d,%f,%f);
                  way["amenity"="taxi"](around:%d,%f,%f);
                  relation["amenity"="taxi"](around:%d,%f,%f);
                );
                out center tags;
                """.formatted(
                    radiusMeters, lat, lng,
                    radiusMeters, lat, lng,
                    radiusMeters, lat, lng,
                    radiusMeters, lat, lng
            );

            String body = "data=" + URLEncoder.encode(overpassQuery, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://overpass-api.de/api/interpreter"))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "e-tekisi/1.0")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("status", "UPSTREAM_ERROR", "ranks", List.of()));
            }

            JsonNode root = objectMapper.readTree(response.body());
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
            cache.put(cacheKey, new CacheEntry(now, now, payload));
            return ResponseEntity.ok(payload);
        } catch (Exception ex) {
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
