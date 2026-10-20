package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.agent.event.AgentEventPublisher;
import com.etekisi.etekisi.agent.guardrail.Geo;
import com.etekisi.etekisi.dto.api.ApiRecords.LocationUpdateRequest;
import com.etekisi.etekisi.model.DriverLocation;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.repository.DriverLocationRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    /** Default and maximum search radius for nearby taxis. */
    static final int DEFAULT_RADIUS_METERS = 5_000;
    static final int MAX_RADIUS_METERS = 10_000;
    private static final int MAX_RESULTS = 50;

    /** What a commuter's map gets: an approximate position, nothing that identifies the driver. */
    public record NearbyTaxi(double lat, double lng) {}

    private final DriverLocationRepository locationRepository;
    private final DriverRepository driverRepository;
    private final AgentEventPublisher agentEvents;
    private final CurrentUser currentUser;

    public LocationController(DriverLocationRepository locationRepository,
                              DriverRepository driverRepository,
                              AgentEventPublisher agentEvents,
                              CurrentUser currentUser) {
        this.locationRepository = locationRepository;
        this.driverRepository = driverRepository;
        this.agentEvents = agentEvents;
        this.currentUser = currentUser;
    }

    /** The driver id comes from the token; any driverId in the body is ignored. */
    @PostMapping("/update")
    public void updateLocation(@Valid @RequestBody LocationUpdateRequest req) {
        Long driverId = currentUser.requireRole(Role.DRIVER);
        DriverLocation location = new DriverLocation();
        location.setDriverId(driverId);
        location.setLat(req.lat());
        location.setLng(req.lng());
        location.setLastUpdated(LocalDateTime.now());
        locationRepository.save(location);
        // Agent listeners attach the ping to any active trip and re-evaluate safety.
        if (req.lat() != null && req.lng() != null) {
            agentEvents.publish(agentEvents.driverLocationUpdated(driverId, req.lat(), req.lng()));
        }
    }

    /**
     * Online taxis near the caller, for the commuter's map.
     *
     * <p>Privacy (POPIA): this used to return every driver's id and exact
     * position to any signed-in user, which let anyone track a named driver.
     * Now it needs the caller's own position, only covers drivers who are
     * online and pinged in the last 5 minutes within {@code radius} metres
     * (default 5 km, max 10 km), rounds positions to 3 decimals (~110 m),
     * and leaves out driver ids.
     */
    @GetMapping("/nearby")
    public List<NearbyTaxi> getNearbyTaxis(@RequestParam double lat,
                                           @RequestParam double lng,
                                           @RequestParam(required = false) Integer radius) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("lat/lng out of range");
        }
        int radiusMeters = Math.min(Math.max(radius != null ? radius : DEFAULT_RADIUS_METERS, 100), MAX_RADIUS_METERS);

        List<DriverLocation> recent = locationRepository.findByLastUpdatedAfter(LocalDateTime.now().minusMinutes(5))
                .stream()
                .filter(l -> l.getLat() != null && l.getLng() != null)
                .filter(l -> Geo.haversineMeters(lat, lng, l.getLat(), l.getLng()) <= radiusMeters)
                .toList();
        if (recent.isEmpty()) return List.of();

        Set<Long> online = driverRepository.findAllById(recent.stream().map(DriverLocation::getDriverId).toList())
                .stream().filter(Driver::isActive).map(Driver::getId).collect(Collectors.toSet());

        return recent.stream()
                .filter(l -> online.contains(l.getDriverId()))
                .sorted(Comparator.comparingDouble(l -> Geo.haversineMeters(lat, lng, l.getLat(), l.getLng())))
                .limit(MAX_RESULTS)
                .map(l -> new NearbyTaxi(round3(l.getLat()), round3(l.getLng())))
                .toList();
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
