package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.BookingRepository;
import com.etekisi.etekisi.repository.NotificationRepository;
import com.etekisi.etekisi.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Requests nobody takes expire; scheduled trips only reach drivers shortly before departure. */
@SpringBootTest
class RequestTimersTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private BookingRepository bookings;
    @Autowired private NotificationRepository notifications;
    @Autowired private BookingService bookingService;

    private long create(MockMvc mvc, User c, String extra) throws Exception {
        String body = mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, c.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLocation\":\"Bree Taxi Rank\",\"destination\":\"Soweto\"" + extra + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"bookingId\":(\\d+).*", "$1"));
    }

    private void shiftExpiry(long id, Instant to) {
        Booking b = bookings.findById(id).orElseThrow();
        b.setRequestExpiresAt(to);
        bookings.save(b);
    }

    private boolean commuterTold(User c, String text) {
        return notifications.findByUserIdAndRoleOrderByCreatedAtDesc(c.getId(), Role.COMMUTER).stream()
                .anyMatch(n -> n.getMessage().contains(text));
    }

    @Test
    void aRequestNobodyTakesExpires() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuterWithBalance("100");
        Driver d = fixtures.driver();
        long id = create(mvc, c, "");

        Booking created = bookings.findById(id).orElseThrow();
        assertThat(created.getReleasedAt()).isNotNull();
        assertThat(Duration.between(Instant.now(), created.getRequestExpiresAt()).toMinutes()).isBetween(14L, 15L);
        mvc.perform(get("/api/bookings/pending").with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isNotEmpty());

        shiftExpiry(id, Instant.now().minusSeconds(1));
        bookingService.releaseAndExpireRequests();

        assertThat(fixtures.status(id)).isEqualTo(BookingStatus.EXPIRED);
        assertThat(commuterTold(c, "No driver took your request from Bree Taxi Rank to Soweto")).isTrue();
        mvc.perform(get("/api/bookings/pending").with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
        mvc.perform(put("/api/bookings/" + id + "/offer").param("fare", "30").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_PENDING"));
        mvc.perform(get("/api/bookings/user/" + c.getId()).with(as(Role.COMMUTER, c.getId())))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].status").value("EXPIRED"));
    }

    @Test
    void anOfferOnAnOverdueRequestIsRefusedEvenBeforeTheJobRuns() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuterWithBalance("100");
        Driver d = fixtures.driver();
        long id = create(mvc, c, "");
        shiftExpiry(id, Instant.now().minusSeconds(1));

        mvc.perform(put("/api/bookings/" + id + "/offer").param("fare", "30").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isConflict());
        assertThat(fixtures.status(id)).isEqualTo(BookingStatus.EXPIRED);
    }

    @Test
    void aLapsedOfferGivesTheCommuterMoreTime() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuterWithBalance("100");
        Driver d = fixtures.driver();
        long id = create(mvc, c, "");
        shiftExpiry(id, Instant.now().plusSeconds(10));
        mvc.perform(put("/api/bookings/" + id + "/offer").param("fare", "30").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isOk());

        Booking offered = bookings.findById(id).orElseThrow();
        offered.setOfferExpiresAt(Instant.now().minusSeconds(1));
        bookings.save(offered);
        bookingService.expireStaleOffers();

        Booking reopened = bookings.findById(id).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(reopened.getRequestExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(4)));
    }

    @Test
    void aScheduledTripReachesDriversShortlyBeforeDeparture() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuterWithBalance("100");
        Driver d = fixtures.driver();
        Instant departure = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        long id = create(mvc, c, ",\"scheduledAt\":\"" + departure + "\"");

        mvc.perform(get("/api/bookings/user/" + c.getId()).with(as(Role.COMMUTER, c.getId())))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].scheduledAt").value(departure.toString()))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].releasedAt").value((Object) null));
        // Hidden from drivers, and can't be taken by id either.
        mvc.perform(get("/api/bookings/pending").with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
        mvc.perform(put("/api/bookings/" + id + "/offer").param("fare", "30").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_YET_RELEASED"));
        Booking b = bookings.findById(id).orElseThrow();
        assertThat(b.getRequestExpiresAt()).isEqualTo(departure.plus(Duration.ofMinutes(15)));

        // Not due yet: the job leaves it alone.
        bookingService.releaseAndExpireRequests();
        assertThat(bookings.findById(id).orElseThrow().getReleasedAt()).isNull();

        // Twenty minutes before departure it's released, and the commuter is told.
        b.setScheduledAt(Instant.now().plus(Duration.ofMinutes(20)));
        bookings.save(b);
        bookingService.releaseAndExpireRequests();
        assertThat(bookings.findById(id).orElseThrow().getReleasedAt()).isNotNull();
        assertThat(commuterTold(c, "We're finding a driver for your")).isTrue();
        mvc.perform(get("/api/bookings/pending").with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isNotEmpty());
        mvc.perform(put("/api/bookings/" + id + "/offer").param("fare", "30").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isOk());
    }

    @Test
    void aTripLeavingWithinHalfAnHourIsShownStraightAway() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuterWithBalance("100");
        long id = create(mvc, c, ",\"scheduledAt\":\"" + Instant.now().plus(Duration.ofMinutes(20)) + "\"");
        assertThat(bookings.findById(id).orElseThrow().getReleasedAt()).isNotNull();
    }

    @Test
    void departureTimesAreChecked() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuterWithBalance("100");
        String base = "{\"pickupLocation\":\"Bree Taxi Rank\",\"destination\":\"Soweto\",";
        for (String[] bad : new String[][]{
                {"\"scheduledAt\":\"" + Instant.now().plus(Duration.ofMinutes(5)) + "\"", "SCHEDULE_TOO_SOON"},
                {"\"scheduledAt\":\"" + Instant.now().plus(Duration.ofDays(20)) + "\"", "SCHEDULE_TOO_FAR"},
                {"\"scheduledTime\":\"tomorrow morning\"", "INVALID_SCHEDULE"}}) {
            mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, c.getId()))
                            .contentType(MediaType.APPLICATION_JSON).content(base + bad[0] + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(bad[1]));
        }

        // The older form: a local South African time from a date-time field.
        LocalDateTime local = LocalDateTime.now(ZoneId.of("Africa/Johannesburg")).plusHours(2).truncatedTo(ChronoUnit.MINUTES);
        long id = create(mvc, c, ",\"scheduledTime\":\"" + local + "\"");
        assertThat(bookings.findById(id).orElseThrow().getScheduledAt())
                .isEqualTo(local.atZone(ZoneId.of("Africa/Johannesburg")).toInstant());
    }
}
