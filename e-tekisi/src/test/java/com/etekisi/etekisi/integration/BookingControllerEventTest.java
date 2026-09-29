package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.agent.event.BookingCreatedEvent;
import com.etekisi.etekisi.agent.event.TripCompletedEvent;
import com.etekisi.etekisi.agent.event.TripStartedEvent;
import com.etekisi.etekisi.agent.observability.CorrelationContext;
import com.etekisi.etekisi.agent.observability.CorrelationIdFilter;
import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-wire integration tests proving the controller layer correctly
 * publishes agent-layer events for the booking lifecycle:
 *   - createBooking -&gt; BookingCreatedEvent
 *   - acceptBooking -&gt; TripStartedEvent
 *   - completeBooking -&gt; TripCompletedEvent
 *
 * These tests exercise the same code path a real HTTP client would, including
 * the security filter chain, the AgentEventPublisher seam, JPA persistence,
 * and Spring's event multicaster.
 */
@SpringBootTest
@RecordApplicationEvents
class BookingControllerEventTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ApplicationEvents events;
    @Autowired private CorrelationIdFilter correlationFilter;
    @Autowired private Fixtures fixtures;

    private static final String BODY = """
            {"pickupLocation":"Park Station","destination":"Bara"}""";

    private MockMvc mvc() {
        // Wire the CorrelationIdFilter explicitly: MockMvc otherwise ignores
        // application filters, and we want to assert correlation-id propagation.
        return TestAuth.mvc(context, correlationFilter);
    }

    @Test
    void createBookingPublishesBookingCreatedEvent() throws Exception {
        User commuter = fixtures.commuter();

        mvc().perform(post("/api/bookings/create").with(as(Role.COMMUTER, commuter.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());

        BookingCreatedEvent evt = events.stream(BookingCreatedEvent.class)
                .filter(e -> commuter.getId().equals(e.commuterId()))
                .findFirst().orElseThrow();
        assertThat(evt.pickupLocation()).isEqualTo("Park Station");
        assertThat(evt.destination()).isEqualTo("Bara");
        assertThat(evt.correlationId()).isNotBlank();
        assertThat(evt.bookingId()).isNotNull();
    }

    @Test
    void correlationIdHeaderIsEchoedAndPropagatedIntoEvent() throws Exception {
        User commuter = fixtures.commuter();
        String corr = "test-corr-" + System.nanoTime();

        MvcResult result = mvc().perform(post("/api/bookings/create").with(as(Role.COMMUTER, commuter.getId()))
                        .header(CorrelationContext.HEADER, corr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andReturn();

        // Filter must echo the inbound id back to the client.
        assertThat(result.getResponse().getHeader(CorrelationContext.HEADER)).isEqualTo(corr);

        // ...AND stamp it onto the published event.
        BookingCreatedEvent evt = events.stream(BookingCreatedEvent.class)
                .filter(e -> corr.equals(e.correlationId()))
                .findFirst()
                .orElseThrow();
        assertThat(evt.commuterId()).isEqualTo(commuter.getId());
    }

    @Test
    void acceptAndCompleteBookingPublishesTripStartedAndCompletedEvents() throws Exception {
        User commuter = fixtures.commuterWithBalance("500.00");
        Driver driver = fixtures.driver();
        Booking b = fixtures.pendingBooking(commuter.getId());

        long startedBefore = events.stream(TripStartedEvent.class).count();
        long completedBefore = events.stream(TripCompletedEvent.class).count();

        mvc().perform(put("/api/bookings/" + b.getId() + "/accept/" + driver.getId())
                        .with(as(Role.DRIVER, driver.getId()))
                        .param("fare", "120.0"))
                .andExpect(status().isOk());

        assertThat(events.stream(TripStartedEvent.class).count() - startedBefore).isEqualTo(1L);
        TripStartedEvent started = events.stream(TripStartedEvent.class)
                .filter(e -> b.getId().equals(e.bookingId()))
                .findFirst().orElseThrow();
        assertThat(started.driverId()).isEqualTo(driver.getId());
        assertThat(started.fare()).isEqualTo(120.0);

        mvc().perform(put("/api/bookings/" + b.getId() + "/complete/" + driver.getId())
                        .with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk());

        assertThat(events.stream(TripCompletedEvent.class).count() - completedBefore).isEqualTo(1L);
        TripCompletedEvent completed = events.stream(TripCompletedEvent.class)
                .filter(e -> b.getId().equals(e.bookingId()))
                .findFirst().orElseThrow();
        assertThat(completed.driverId()).isEqualTo(driver.getId());
        assertThat(completed.fare()).isEqualTo(120.0);
    }
}
