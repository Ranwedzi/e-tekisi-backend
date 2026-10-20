package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Something changed" events reach the right people, after commit, and nobody else. */
@SpringBootTest
class LiveEventsTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private BookingRepository bookings;
    @Autowired private TransactionTemplate tx;

    private MvcResult open(MockMvc mvc, Role role, long id) throws Exception {
        MvcResult r = mvc.perform(get("/api/events").with(as(role, id)))
                .andExpect(request().asyncStarted())
                .andReturn();
        waitFor(r, "event:ready");
        return r;
    }

    private static void waitFor(MvcResult r, String text) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (r.getResponse().getContentAsString().contains(text)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("stream never got '" + text + "':\n" + r.getResponse().getContentAsString());
    }

    private static void settle() throws InterruptedException {
        Thread.sleep(300); // events go out on a background thread
    }

    @Test
    void anOfferReachesTheCommuterAndOtherDriversButNotOtherCommuters() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User commuter = fixtures.commuter();
        User other = fixtures.commuter();
        Driver driver = fixtures.driver();
        Driver otherDriver = fixtures.driver();
        Booking b = fixtures.pendingBooking(commuter.getId());

        MvcResult commuterStream = open(mvc, Role.COMMUTER, commuter.getId());
        MvcResult otherStream = open(mvc, Role.COMMUTER, other.getId());
        MvcResult driverStream = open(mvc, Role.DRIVER, otherDriver.getId());

        mvc.perform(put("/api/bookings/" + b.getId() + "/offer").param("fare", "30").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk());

        waitFor(commuterStream, "data:bookings");
        waitFor(commuterStream, "data:notifications"); // "R30 offer from ..."
        waitFor(driverStream, "data:pending");          // the request left the open list
        settle();
        assertThat(otherStream.getResponse().getContentAsString()).doesNotContain("event:change");
        // Only topics, never data.
        assertThat(commuterStream.getResponse().getContentAsString()).doesNotContain("30").doesNotContain("Park Station");
    }

    @Test
    void chatMessagesReachBothParticipants() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User commuter = fixtures.commuter();
        Driver driver = fixtures.driver();
        Booking b = fixtures.pendingBooking(commuter.getId());
        b.setDriverId(driver.getId());
        b.setStatus(BookingStatus.ACCEPTED);
        bookings.save(b);

        MvcResult driverStream = open(mvc, Role.DRIVER, driver.getId());
        mvc.perform(post("/api/messages/send").with(as(Role.COMMUTER, commuter.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":" + b.getId() + ",\"content\":\"I'm at the gate\"}"))
                .andExpect(status().isOk());
        waitFor(driverStream, "data:messages:" + b.getId());
    }

    @Test
    void aRolledBackChangeSendsNothing() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User commuter = fixtures.commuter();
        Booking b = fixtures.pendingBooking(commuter.getId());
        MvcResult stream = open(mvc, Role.COMMUTER, commuter.getId());

        try {
            tx.executeWithoutResult(s -> {
                Booking x = bookings.findById(b.getId()).orElseThrow();
                x.setDestination("Changed then rolled back");
                bookings.saveAndFlush(x);
                throw new IllegalStateException("roll back");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        settle();
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("data:bookings");
    }

    @Test
    void streamsNeedASignIn() throws Exception {
        TestAuth.mvc(context).perform(get("/api/events")).andExpect(status().isUnauthorized());
    }
}
