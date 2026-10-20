package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.BookingRepository;
import com.etekisi.etekisi.repository.DriverLocationRepository;
import com.etekisi.etekisi.repository.TripShareRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** "Share my trip": someone trusted can follow the taxi until the trip ends. */
@SpringBootTest
class TripShareTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private BookingRepository bookings;
    @Autowired private DriverLocationRepository locations;
    @Autowired private TripShareRepository shares;

    private Booking acceptedTrip(User commuter, Driver driver) {
        Booking b = fixtures.pendingBooking(commuter.getId());
        b.setDriverId(driver.getId());
        b.setStatus(BookingStatus.ACCEPTED);
        return bookings.save(b);
    }

    private String share(MockMvc mvc, long bookingId, long commuterId) throws Exception {
        String body = mvc.perform(post("/api/bookings/" + bookingId + "/share").with(as(Role.COMMUTER, commuterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("http://localhost:3000/share/")))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("token").asText();
    }

    @Test
    void theLinkShowsTheTaxiUntilTheTripEnds() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User commuter = fixtures.commuter();
        Driver driver = fixtures.driver();
        DriverLocation l = new DriverLocation();
        l.setDriverId(driver.getId());
        l.setLat(-26.2041);
        l.setLng(28.0473);
        l.setLastUpdated(LocalDateTime.now());
        locations.save(l);
        Booking trip = acceptedTrip(commuter, driver);

        String token = share(mvc, trip.getId(), commuter.getId());
        assertThat(shares.findAll()).noneMatch(s -> s.getTokenHash().equals(token)); // only the hash is stored

        // No sign-in needed: the link is the key.
        mvc.perform(get("/api/share/" + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.commuterFirstName").value("Test"))
                .andExpect(jsonPath("$.driverFirstName").value("Test"))
                .andExpect(jsonPath("$.taxiPlate").value("ABC123GP"))
                .andExpect(jsonPath("$.taxiPosition.lat").value(-26.2041))
                .andExpect(jsonPath("$.taxiPosition.stale").value(false))
                // Nothing else about either person.
                .andExpect(jsonPath("$.phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.fare").doesNotExist());

        trip.setStatus(BookingStatus.COMPLETED);
        bookings.save(trip);
        mvc.perform(get("/api/share/" + token))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.taxiPosition").doesNotExist())
                .andExpect(jsonPath("$.taxiPlate").doesNotExist());
    }

    @Test
    void theCommuterCanStopSharing() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User commuter = fixtures.commuter();
        Booking trip = acceptedTrip(commuter, fixtures.driver());
        String token = share(mvc, trip.getId(), commuter.getId());
        mvc.perform(delete("/api/bookings/" + trip.getId() + "/share").with(as(Role.COMMUTER, commuter.getId())))
                .andExpect(jsonPath("$.stopped").value(1));
        mvc.perform(get("/api/share/" + token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/share/not-a-real-token")).andExpect(status().isNotFound());
    }

    @Test
    void onlyTheCommuterOfAnAcceptedTripCanShareIt() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User commuter = fixtures.commuter();
        User other = fixtures.commuter();
        Booking pending = fixtures.pendingBooking(commuter.getId());
        mvc.perform(post("/api/bookings/" + pending.getId() + "/share").with(as(Role.COMMUTER, commuter.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_ACTIVE"));
        Booking trip = acceptedTrip(commuter, fixtures.driver());
        mvc.perform(post("/api/bookings/" + trip.getId() + "/share").with(as(Role.COMMUTER, other.getId())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/" + trip.getId() + "/share").with(as(Role.DRIVER, trip.getDriverId())))
                .andExpect(status().isForbidden());
    }
}
