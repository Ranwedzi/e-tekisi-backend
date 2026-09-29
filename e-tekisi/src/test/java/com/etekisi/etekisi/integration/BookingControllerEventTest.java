package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.agent.event.BookingCreatedEvent;
import com.etekisi.etekisi.agent.event.TripCompletedEvent;
import com.etekisi.etekisi.agent.event.TripStartedEvent;
import com.etekisi.etekisi.agent.observability.CorrelationContext;
import com.etekisi.etekisi.agent.observability.CorrelationIdFilter;
import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.User;
import com.etekisi.etekisi.model.Wallet;
import com.etekisi.etekisi.repository.BookingRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.UserRepository;
import com.etekisi.etekisi.repository.WalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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
 * the AgentEventPublisher seam, JPA persistence, and Spring's event multicaster.
 */
@SpringBootTest
@RecordApplicationEvents
class BookingControllerEventTest {

    @Autowired private WebApplicationContext context;
    @Autowired private BookingRepository bookings;
    @Autowired private DriverRepository drivers;
    @Autowired private UserRepository users;
    @Autowired private WalletRepository wallets;
    @Autowired private ApplicationEvents events;
    @Autowired private CorrelationIdFilter correlationFilter;

    private final ObjectMapper json = new ObjectMapper();

    private MockMvc mvc() {
        // Wire the CorrelationIdFilter explicitly: MockMvc otherwise ignores
        // application filters, and we want to assert correlation-id propagation.
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationFilter)
                .build();
    }

    @Test
    void createBookingPublishesBookingCreatedEvent() throws Exception {
        Booking req = new Booking();
        req.setCommuterId(101L);
        req.setPickupLocation("Park Station");
        req.setDestination("Bara");

        mvc().perform(post("/api/bookings/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk());

        long count = events.stream(BookingCreatedEvent.class).count();
        assertThat(count).isEqualTo(1L);

        BookingCreatedEvent evt = events.stream(BookingCreatedEvent.class).findFirst().orElseThrow();
        assertThat(evt.commuterId()).isEqualTo(101L);
        assertThat(evt.pickupLocation()).isEqualTo("Park Station");
        assertThat(evt.destination()).isEqualTo("Bara");
        assertThat(evt.correlationId()).isNotBlank();
        assertThat(evt.bookingId()).isNotNull();
    }

    @Test
    void correlationIdHeaderIsEchoedAndPropagatedIntoEvent() throws Exception {
        Booking req = new Booking();
        req.setCommuterId(202L);
        req.setPickupLocation("Bree Taxi Rank");
        req.setDestination("Sandton");
        String corr = "test-corr-" + System.nanoTime();

        MvcResult result = mvc().perform(post("/api/bookings/create")
                        .header(CorrelationContext.HEADER, corr)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        // Filter must echo the inbound id back to the client.
        assertThat(result.getResponse().getHeader(CorrelationContext.HEADER)).isEqualTo(corr);

        // ...AND stamp it onto the published event.
        BookingCreatedEvent evt = events.stream(BookingCreatedEvent.class)
                .filter(e -> corr.equals(e.correlationId()))
                .findFirst()
                .orElseThrow();
        assertThat(evt.commuterId()).isEqualTo(202L);
    }

    @Test
    void acceptAndCompleteBookingPublishesTripStartedAndCompletedEvents() throws Exception {
        // Seed: commuter, driver, wallet with funds, booking in PENDING.
        User commuter = new User();
        commuter.setFullName("Test Commuter");
        commuter.setEmail("c@test");
        commuter.setPhoneNumber("0000000000");
        commuter.setPassword("x");
        commuter = users.save(commuter);

        Driver driver = new Driver();
        driver.setFullName("Test Driver");
        driver.setEmail("d@test");
        driver.setPhoneNumber("1111111111");
        driver.setLicenseNumber("L1");
        driver.setTaxiPlate("ABC123GP");
        driver.setPassword("x");
        driver = drivers.save(driver);

        Wallet w = new Wallet();
        w.setUserId(commuter.getId());
        w.setRole("COMMUTER");
        w.setBalance(500.0);
        w.setTotalTrips(0);
        wallets.save(w);

        Booking newBooking = new Booking();
        newBooking.setCommuterId(commuter.getId());
        newBooking.setPickupLocation("Park Station");
        newBooking.setDestination("Bara");
        newBooking.setStatus("PENDING");
        final Booking b = bookings.save(newBooking);

        long startedBefore = events.stream(TripStartedEvent.class).count();
        long completedBefore = events.stream(TripCompletedEvent.class).count();

        // Accept
        mvc().perform(put("/api/bookings/" + b.getId() + "/accept/" + driver.getId())
                        .param("fare", "120.0"))
                .andExpect(status().isOk());

        long startedAfter = events.stream(TripStartedEvent.class).count();
        assertThat(startedAfter - startedBefore).isEqualTo(1L);
        TripStartedEvent started = events.stream(TripStartedEvent.class)
                .filter(e -> b.getId().equals(e.bookingId()))
                .findFirst().orElseThrow();
        assertThat(started.driverId()).isEqualTo(driver.getId());
        assertThat(started.fare()).isEqualTo(120.0);

        // Complete
        mvc().perform(put("/api/bookings/" + b.getId() + "/complete/" + driver.getId()))
                .andExpect(status().isOk());

        long completedAfter = events.stream(TripCompletedEvent.class).count();
        assertThat(completedAfter - completedBefore).isEqualTo(1L);
        TripCompletedEvent completed = events.stream(TripCompletedEvent.class)
                .filter(e -> b.getId().equals(e.bookingId()))
                .findFirst().orElseThrow();
        assertThat(completed.driverId()).isEqualTo(driver.getId());
        assertThat(completed.fare()).isEqualTo(120.0);
    }
}
