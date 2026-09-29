package com.etekisi.etekisi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/geo")
public class GeoController {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/geocode")
    public ResponseEntity<Map<String, Object>> geocode(@RequestParam String query) {
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID_QUERY"));
        }

        try {
            String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            String url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + encoded;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "e-tekisi/1.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("status", "UPSTREAM_ERROR"));
            }

            JsonNode root = objectMapper.readTree(response.body());
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
        } catch (Exception ex) {
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
        try {
            String url = String.format(
                    "https://router.project-osrm.org/route/v1/driving/%s,%s;%s,%s?overview=full&geometries=geojson",
                    fromLng, fromLat, toLng, toLat
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "e-tekisi/1.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("status", "UPSTREAM_ERROR"));
            }

            JsonNode root = objectMapper.readTree(response.body());
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
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("status", "ERROR", "message", "Route fetch failed"));
        }
    }
}
