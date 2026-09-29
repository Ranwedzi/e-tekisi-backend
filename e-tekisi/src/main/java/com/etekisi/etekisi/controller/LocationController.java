package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.agent.event.AgentEventPublisher;
import com.etekisi.etekisi.model.DriverLocation; // IMPORT ADDED
import com.etekisi.etekisi.repository.DriverLocationRepository; // IMPORT ADDED
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    @Autowired
    private DriverLocationRepository locationRepository;

    @Autowired
    private AgentEventPublisher agentEvents;

    @PostMapping("/update")
    public void updateLocation(@RequestBody DriverLocation location) {
        location.setLastUpdated(LocalDateTime.now());
        locationRepository.save(location);
        // Agent listeners attach the ping to any active trip and re-evaluate safety.
        if (location.getDriverId() != null && location.getLat() != null && location.getLng() != null) {
            agentEvents.publish(agentEvents.driverLocationUpdated(
                    location.getDriverId(), location.getLat(), location.getLng()));
        }
    }

    @GetMapping("/nearby")
    public List<DriverLocation> getNearbyTaxis() {
        // Returns drivers who updated their location in the last 5 minutes
        return locationRepository.findByLastUpdatedAfter(LocalDateTime.now().minusMinutes(5));
    }
}