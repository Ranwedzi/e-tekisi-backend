package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.agent.event.AgentEventPublisher;
import com.etekisi.etekisi.dto.api.ApiRecords.LocationUpdateRequest;
import com.etekisi.etekisi.model.DriverLocation;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.DriverLocationRepository;
import com.etekisi.etekisi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final DriverLocationRepository locationRepository;
    private final AgentEventPublisher agentEvents;
    private final CurrentUser currentUser;

    public LocationController(DriverLocationRepository locationRepository,
                              AgentEventPublisher agentEvents,
                              CurrentUser currentUser) {
        this.locationRepository = locationRepository;
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

    @GetMapping("/nearby")
    public List<DriverLocation> getNearbyTaxis() {
        // Returns drivers who updated their location in the last 5 minutes
        return locationRepository.findByLastUpdatedAfter(LocalDateTime.now().minusMinutes(5));
    }
}
