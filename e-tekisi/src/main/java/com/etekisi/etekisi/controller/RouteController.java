package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.Route;
import com.etekisi.etekisi.repository.RouteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

    @Autowired
    private RouteRepository routeRepository;

    @GetMapping("/search")
    public Map<String, Object> searchRoute(@RequestParam String from, @RequestParam String to) {
        // We try to find a saved route first
        var savedRoute = routeRepository.findByStartLocationIgnoreCaseAndDestinationIgnoreCase(from, to);

        Map<String, Object> response = new HashMap<>();

        if (savedRoute.isPresent()) {
            response.put("status", "FOUND");
            response.put("data", savedRoute.get());
        } else {
            // This is where the AI Agent will hook in later
            response.put("status", "AI_ANALYSIS_REQUIRED");
            response.put("message", "analyzing the best route from " + from + " to " + to);
            response.put("suggestedRank", "Analyzing local ranks...");
            response.put("price", "Calculating...");
            response.put("estimatedTime", "Calculating...");
        }

        return response;
    }
}