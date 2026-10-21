package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.agent.insight.InsightCode;
import com.etekisi.etekisi.agent.insight.OperationalInsightRepository;
import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.DriverLocationRepository;
import com.etekisi.etekisi.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Pick-up coordinates, the driver arriving, the trip starting, and ratings both ways. */
@SpringBootTest
class TripProgressTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private NotificationRepository notifications;
    @Autowired private DriverLocationRepository locations;
    @Autowired private OperationalInsightRepository insights;

    private long createWithCoords(MockMvc mvc, User c) throws Exception {
        String body = mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pickupLocation":"Bree Taxi Rank","destination":"Soweto",
                                 "pickupLat":-26.2005,"pickupLng":28.0368,"destinationLat":-26.2485,"destinationLng":27.8540}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.pickupLat").value(-26.2005))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"bookingId\":(\\d+).*", "$1"));
    }

    @Test
    void aTripFromRequestToRatings() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuterWithBalance("100");
        Driver d = fixtures.driver();
        long id = createWithCoords(mvc, c);

        // Drivers see where the pick-up is.
        mvc.perform(get("/api/bookings/pending").with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].pickupLng").value(28.0368));

        mvc.perform(put("/api/bookings/" + id + "/offer").param("fare", "30").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isOk());
        mvc.perform(put("/api/bookings/" + id + "/accept-offer").with(as(Role.COMMUTER, c.getId())))
                .andExpect(status().isOk());

        // The commuter can see the taxi coming.
        DriverLocation l = new DriverLocation();
        l.setDriverId(d.getId());
        l.setLat(-26.21);
        l.setLng(28.04);
        l.setLastUpdated(LocalDateTime.now());
        locations.save(l);
        mvc.perform(get("/api/bookings/" + id + "/driver-position").with(as(Role.COMMUTER, c.getId())))
                .andExpect(jsonPath("$.lat").value(-26.21))
                .andExpect(jsonPath("$.stale").value(false));
        mvc.perform(get("/api/bookings/" + id + "/driver-position").with(as(Role.COMMUTER, fixtures.commuter().getId())))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/bookings/" + id + "/arrived").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivedAt").isNotEmpty());
        assertThat(notifications.findByUserIdAndRoleOrderByCreatedAtDesc(c.getId(), Role.COMMUTER))
                .anyMatch(n -> n.getMessage().contains("Your driver has arrived at Bree Taxi Rank. Look for ABC123GP"));

        mvc.perform(put("/api/bookings/" + id + "/start").with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$.startedAt").isNotEmpty());
        // On board: no cancelling for a refund now.
        mvc.perform(put("/api/bookings/" + id + "/cancel").with(as(Role.COMMUTER, c.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_STARTED"));

        // Can't rate before the end.
        mvc.perform(post("/api/bookings/" + id + "/rating").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stars\":5}"))
                .andExpect(jsonPath("$.code").value("TRIP_NOT_COMPLETED"));
        mvc.perform(put("/api/bookings/" + id + "/complete/" + d.getId()).with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$.booking.completedAt").isNotEmpty());

        mvc.perform(post("/api/bookings/" + id + "/rating").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stars\":4,\"comment\":\"Friendly, drove carefully\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateeAverage").value(4.0));
        mvc.perform(post("/api/bookings/" + id + "/rating").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stars\":1}"))
                .andExpect(jsonPath("$.code").value("ALREADY_RATED"));
        mvc.perform(post("/api/bookings/" + id + "/rating").with(as(Role.DRIVER, d.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stars\":5}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/bookings/user/" + c.getId()).with(as(Role.COMMUTER, c.getId())))
                .andExpect(jsonPath("$[0].driverRating").value(4.0))
                .andExpect(jsonPath("$[0].driverRatingCount").value(1))
                .andExpect(jsonPath("$[0].commuterRated").value(true))
                .andExpect(jsonPath("$[0].driverRated").value(true));

        // The commuter's rating now shows on their next request (without their name).
        long next = createWithCoords(mvc, c);
        mvc.perform(get("/api/bookings/pending").with(as(Role.DRIVER, fixtures.driver().getId())))
                .andExpect(jsonPath("$[?(@.id == " + next + ")].commuterRating").value(5.0))
                .andExpect(jsonPath("$[?(@.id == " + next + ")].commuterName").value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void aOneStarRatingIsFlaggedToOperators() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuterWithBalance("100");
        Driver d = fixtures.driver();
        long id = createWithCoords(mvc, c);
        mvc.perform(put("/api/bookings/" + id + "/offer").param("fare", "30").with(as(Role.DRIVER, d.getId())));
        mvc.perform(put("/api/bookings/" + id + "/accept-offer").with(as(Role.COMMUTER, c.getId())));
        mvc.perform(put("/api/bookings/" + id + "/complete/" + d.getId()).with(as(Role.DRIVER, d.getId())));
        mvc.perform(post("/api/bookings/" + id + "/rating").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stars\":1,\"comment\":\"Was on his phone the whole way\"}"))
                .andExpect(status().isOk());
        assertThat(insights.findByBookingIdOrderByCreatedAtAsc(id))
                .anyMatch(i -> i.getCode() == InsightCode.DRIVER_LOW_RATING && i.getMessage().contains("on his phone"));
    }

    @Test
    void coordinatesComeInPairsAndOnlyTripPeopleRate() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuter();
        mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, c.getId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLocation\":\"Bree\",\"destination\":\"Soweto\",\"pickupLat\":-26.2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COORDINATES"));
        mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, c.getId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLocation\":\"Bree\",\"destination\":\"Soweto\",\"pickupLat\":-126.2,\"pickupLng\":28}"))
                .andExpect(status().isBadRequest());
        Booking b = fixtures.pendingBooking(c.getId());
        mvc.perform(post("/api/bookings/" + b.getId() + "/rating").with(as(Role.COMMUTER, fixtures.commuter().getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stars\":5}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/bookings/" + b.getId() + "/arrived").with(as(Role.DRIVER, fixtures.driver().getId())))
                .andExpect(status().isForbidden());
    }
}
