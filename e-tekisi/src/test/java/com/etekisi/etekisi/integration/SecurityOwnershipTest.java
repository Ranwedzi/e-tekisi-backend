package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.NotificationRepository;
import com.etekisi.etekisi.repository.SavedRankRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every endpoint that used to trust an id from the URL or body: the owner
 * gets through, anyone else gets 403 (or 401 without a token).
 */
@SpringBootTest
class SecurityOwnershipTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private NotificationRepository notifications;
    @Autowired private SavedRankRepository savedRanks;

    private MockMvc mvc;
    private User alice;
    private User bob;
    private Driver dave;
    private Driver erin;

    @BeforeEach
    void setUp() {
        mvc = TestAuth.mvc(context);
        alice = fixtures.commuterWithBalance("100.00");
        bob = fixtures.commuterWithBalance("100.00");
        dave = fixtures.driver();
        erin = fixtures.driver();
    }

    @Test
    void noTokenMeans401() throws Exception {
        mvc.perform(get("/api/wallet/" + alice.getId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/user/" + alice.getId())).andExpect(status().isUnauthorized());
    }

    @Test
    void profilesAreOwnerOnly() throws Exception {
        mvc.perform(get("/api/auth/commuter/" + alice.getId()).with(as(Role.COMMUTER, alice.getId())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/commuter/" + alice.getId()).with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        // Same numeric id, wrong table: a driver can't read the commuter with their id.
        mvc.perform(get("/api/auth/commuter/" + dave.getId()).with(as(Role.DRIVER, dave.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/auth/driver/" + dave.getId() + "/status").param("active", "true")
                        .with(as(Role.DRIVER, erin.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/auth/driver/" + dave.getId() + "/status").param("active", "true")
                        .with(as(Role.DRIVER, dave.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void walletIsOwnerOnly() throws Exception {
        mvc.perform(get("/api/wallet/" + alice.getId()).with(as(Role.COMMUTER, alice.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.0));
        mvc.perform(get("/api/wallet/" + alice.getId()).with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/wallet/" + alice.getId() + "/topup").param("amount", "50")
                        .with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        assertThat(fixtures.walletBalance(alice.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void bookingListsAreOwnerOnly() throws Exception {
        mvc.perform(get("/api/bookings/user/" + alice.getId()).with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/bookings/driver/" + dave.getId()).with(as(Role.DRIVER, erin.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/bookings/driver/" + dave.getId() + "/earnings").with(as(Role.DRIVER, erin.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/bookings/pending").with(as(Role.COMMUTER, alice.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void createBookingIgnoresCommuterIdInBody() throws Exception {
        mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, alice.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commuterId\":" + bob.getId() + ",\"pickupLocation\":\"A\",\"destination\":\"B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.commuterId").value(alice.getId()));
    }

    @Test
    void driversCanOnlyActAsThemselves() throws Exception {
        Booking b = fixtures.pendingBooking(alice.getId());
        mvc.perform(put("/api/bookings/" + b.getId() + "/accept/" + dave.getId()).param("fare", "20")
                        .with(as(Role.DRIVER, erin.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/" + b.getId() + "/decline/" + dave.getId())
                        .with(as(Role.DRIVER, erin.getId())))
                .andExpect(status().isForbidden());
        // A commuter can't accept at all.
        mvc.perform(put("/api/bookings/" + b.getId() + "/accept/" + dave.getId()).param("fare", "20")
                        .with(as(Role.COMMUTER, alice.getId())))
                .andExpect(status().isForbidden());
        assertThat(fixtures.status(b.getId())).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void onlyParticipantsCanCancelChatOrPanic() throws Exception {
        Booking b = fixtures.pendingBooking(alice.getId());
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        // The old ?role=DRIVER trick no longer changes anything.
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").param("role", "DRIVER")
                        .with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/messages/" + b.getId()).with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/messages/send").with(as(Role.COMMUTER, bob.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":" + b.getId() + ",\"content\":\"hi\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agents/safety/panic").param("bookingId", String.valueOf(b.getId()))
                        .with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());

        // The booking's own commuter can do all three.
        mvc.perform(post("/api/messages/send").with(as(Role.COMMUTER, alice.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":" + b.getId() + ",\"senderId\":" + bob.getId() + ",\"content\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderId").value(alice.getId()))
                .andExpect(jsonPath("$.senderRole").value("COMMUTER"));
        mvc.perform(post("/api/agents/safety/panic").param("bookingId", String.valueOf(b.getId()))
                        .with(as(Role.COMMUTER, alice.getId())))
                .andExpect(status().isOk());
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").with(as(Role.COMMUTER, alice.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void notificationsAreOwnerOnly() throws Exception {
        DashboardNotification n = new DashboardNotification();
        n.setUserId(alice.getId());
        n.setRole(Role.COMMUTER);
        n.setMessage("hello");
        n = notifications.save(n);

        mvc.perform(get("/api/notifications").with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + n.getId() + ")]").isEmpty());
        mvc.perform(put("/api/notifications/read/" + n.getId()).with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/notifications/" + n.getId()).with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        // A driver whose id happens to equal alice's id still isn't alice.
        mvc.perform(delete("/api/notifications/" + n.getId()).with(as(Role.DRIVER, alice.getId())))
                .andExpect(status().isForbidden());
        // The old "delete everyone's notifications" endpoint is gone.
        mvc.perform(delete("/api/notifications/clear-all").with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().is4xxClientError());
        assertThat(notifications.findById(n.getId())).isPresent();

        mvc.perform(get("/api/notifications/unread-count").with(as(Role.COMMUTER, alice.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(put("/api/notifications/read-all").with(as(Role.COMMUTER, alice.getId())))
                .andExpect(status().isOk());
        assertThat(notifications.findById(n.getId()).orElseThrow().isRead()).isTrue();
    }

    @Test
    void payoutsAreDriverOwnerOnly() throws Exception {
        String body = "{\"amount\":20,\"method\":\"FNB_EWALLET\",\"phoneNumber\":\"0821234567\"}";
        mvc.perform(post("/api/payouts/driver/" + dave.getId() + "/request").with(as(Role.DRIVER, erin.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/payouts/driver/" + dave.getId()).with(as(Role.DRIVER, erin.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/payouts/driver/" + dave.getId()).with(as(Role.DRIVER, dave.getId())))
                .andExpect(status().isOk());
    }

    @Test
    void savedRanksAreOwnerOnly() throws Exception {
        SavedRank r = new SavedRank();
        r.setCommuterId(alice.getId());
        r.setName("Home");
        r.setAddress("Bree Taxi Rank");
        r = savedRanks.save(r);
        mvc.perform(get("/api/saved-ranks/" + alice.getId()).with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/saved-ranks/" + r.getId()).with(as(Role.COMMUTER, bob.getId())))
                .andExpect(status().isForbidden());
        assertThat(savedRanks.findById(r.getId())).isPresent();
    }

    @Test
    void queueTicketsAreOwnerOnly() throws Exception {
        String ticketJson = mvc.perform(post("/api/queue/join").with(as(Role.DRIVER, dave.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taxiRank\":\"Bree\",\"driverId\":" + erin.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(dave.getId()))
                .andReturn().getResponse().getContentAsString();
        long ticketId = Long.parseLong(ticketJson.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(put("/api/queue/skip/" + ticketId).with(as(Role.DRIVER, erin.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/queue/complete/" + ticketId).with(as(Role.DRIVER, erin.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorEndpointsRejectCommutersAndDrivers() throws Exception {
        mvc.perform(get("/api/insights").with(as(Role.COMMUTER, alice.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/agents/hotspots").with(as(Role.DRIVER, dave.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/agents/hotspots").with(as(Role.OPERATOR, 1L)))
                .andExpect(status().isOk());
    }
}
