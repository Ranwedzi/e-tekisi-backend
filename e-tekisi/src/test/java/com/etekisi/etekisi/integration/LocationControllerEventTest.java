package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.agent.event.DriverLocationUpdatedEvent;
import com.etekisi.etekisi.model.DriverLocation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-wire integration test: POST /api/locations/update must publish a
 * DriverLocationUpdatedEvent so safety/trip-gps listeners can react.
 *
 * Also asserts the anti-hallucination contract: requests with missing
 * lat/lng MUST persist the row but MUST NOT publish an event (the listener
 * pipeline cannot reason on partial pings).
 */
@SpringBootTest
@RecordApplicationEvents
class LocationControllerEventTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ApplicationEvents events;

    private final ObjectMapper json = new ObjectMapper();

    private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).build(); }

    @Test
    void locationUpdatePublishesEvent() throws Exception {
        DriverLocation loc = new DriverLocation();
        loc.setDriverId(7001L);
        loc.setLat(-26.2041);
        loc.setLng(28.0473);

        mvc().perform(post("/api/locations/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(loc)))
                .andExpect(status().isOk());

        DriverLocationUpdatedEvent evt = events.stream(DriverLocationUpdatedEvent.class)
                .filter(e -> Long.valueOf(7001L).equals(e.driverId()))
                .findFirst()
                .orElseThrow();
        assertThat(evt.lat()).isEqualTo(-26.2041);
        assertThat(evt.lng()).isEqualTo(28.0473);
        assertThat(evt.correlationId()).isNotBlank();
    }

    @Test
    void locationUpdateWithMissingCoordsDoesNotPublishEvent() throws Exception {
        // Anti-hallucination: a partial ping is persisted to the legacy table
        // but the agent layer must NOT receive an event it cannot reason on.
        DriverLocation loc = new DriverLocation();
        loc.setDriverId(7002L);
        // lat/lng intentionally null

        mvc().perform(post("/api/locations/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(loc)))
                .andExpect(status().isOk());

        long count = events.stream(DriverLocationUpdatedEvent.class)
                .filter(e -> Long.valueOf(7002L).equals(e.driverId()))
                .count();
        assertThat(count).isZero();
    }
}
