package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.agent.event.DriverLocationUpdatedEvent;
import com.etekisi.etekisi.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static com.etekisi.etekisi.integration.TestAuth.as;
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

    private MockMvc mvc() { return TestAuth.mvc(context); }

    @Test
    void locationUpdatePublishesEvent() throws Exception {
        // The driver id comes from the token, not the body.
        mvc().perform(post("/api/locations/update").with(as(Role.DRIVER, 7001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":-26.2041,\"lng\":28.0473}"))
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
        // lat/lng intentionally absent
        mvc().perform(post("/api/locations/update").with(as(Role.DRIVER, 7002L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        long count = events.stream(DriverLocationUpdatedEvent.class)
                .filter(e -> Long.valueOf(7002L).equals(e.driverId()))
                .count();
        assertThat(count).isZero();
    }

    @Test
    void commutersCannotPostDriverLocations() throws Exception {
        mvc().perform(post("/api/locations/update").with(as(Role.COMMUTER, 7003L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":-26.2,\"lng\":28.0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc().perform(post("/api/locations/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":-26.2,\"lng\":28.0}"))
                .andExpect(status().isUnauthorized());
    }
}
