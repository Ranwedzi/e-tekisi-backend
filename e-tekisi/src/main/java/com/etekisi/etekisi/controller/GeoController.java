package com.etekisi.etekisi.controller;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Geocoding and routing for the map, via {@link OsmGateway} (cached and rate-limited). */
@RestController
@RequestMapping("/api/geo")
public class GeoController {

    private final OsmGateway osm;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeoController(OsmGateway osm) {
        this.osm = osm;
    }

    @GetMapping("/geocode")
    public ResponseEntity<Map<String, Object>> geocode(@RequestParam String query) {
        if (query == null || query.trim().isEmpty() || query.length() > 300) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID_QUERY"));
        }

        try {
            JsonNode root = objectMapper.readTree(osm.geocode(query));
            if (!root.isArray() || root.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "NOT_FOUND"));
            }

            JsonNode first = root.get(0);
            double lat = first.path("lat").asDouble(Double.NaN);
            double lng = first.path("lon").asDouble(Double.NaN);

            if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
                return ResponseEntity.ok(Map.of("status", "NOT_FOUND"));
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "OK");
            payload.put("lat", lat);
            payload.put("lng", lng);
            return ResponseEntity.ok(payload);
        } catch (OsmGateway.UpstreamException | java.io.IOException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("status", "ERROR", "message", "Geocoding failed"));
        }
    }

    @GetMapping("/route")
    public ResponseEntity<Map<String, Object>> route(
            @RequestParam double fromLat,
            @RequestParam double fromLng,
            @RequestParam double toLat,
            @RequestParam double toLng
    ) {
        if (!validLatLng(fromLat, fromLng) || !validLatLng(toLat, toLng)) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID_COORDINATES"));
        }
        try {
            JsonNode root = objectMapper.readTree(osm.route(fromLat, fromLng, toLat, toLng));
            JsonNode coords = root.path("routes").path(0).path("geometry").path("coordinates");
            if (!coords.isArray() || coords.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "NO_ROUTE"));
            }

            List<List<Double>> points = new ArrayList<>();
            for (JsonNode node : coords) {
                if (node.isArray() && node.size() >= 2) {
                    double lng = node.get(0).asDouble(Double.NaN);
                    double lat = node.get(1).asDouble(Double.NaN);
                    if (Double.isFinite(lat) && Double.isFinite(lng)) {
                        points.add(List.of(lat, lng));
                    }
                }
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", points.isEmpty() ? "NO_ROUTE" : "OK");
            payload.put("points", points);
            return ResponseEntity.ok(payload);
        } catch (OsmGateway.UpstreamException | java.io.IOException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("status", "ERROR", "message", "Route fetch failed"));
        }
    }

    private static boolean validLatLng(double lat, double lng) {
        return lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }
}
