package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.agent.observability.CorrelationIdFilter;
import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.User;
import com.etekisi.etekisi.repository.BookingRepository;
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

@SpringBootTest
class ErrorMappingAndIdempotencyTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private BookingRepository bookings;
    @Autowired private CorrelationIdFilter correlationFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = TestAuth.mvc(context, correlationFilter); }

    // ------------------------------------------------ status codes (not 500) --

    @Test
    void unknownBookingIs404() throws Exception {
        Driver d = fixtures.driver();
        mvc.perform(put("/api/bookings/999999/accept/" + d.getId()).param("fare", "10")
                        .with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void missingParameterIs400() throws Exception {
        Driver d = fixtures.driver();
        mvc.perform(put("/api/bookings/1/accept/" + d.getId()).with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongMethodIs405() throws Exception {
        User u = fixtures.commuter();
        mvc.perform(delete("/api/bookings/create").with(as(Role.COMMUTER, u.getId())))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void invalidPayoutMethodIs400NotA500() throws Exception {
        Driver d = fixtures.driver();
        mvc.perform(post("/api/payouts/driver/" + d.getId() + "/request").with(as(Role.DRIVER, d.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":20,\"method\":\"BITCOIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYOUT_METHOD"));
    }

    // ---------------------------------------------------------- idempotency --

    @Test
    void sameKeyTwiceCreatesOneBooking() throws Exception {
        User u = fixtures.commuter();
        String key = "idem-" + System.nanoTime();
        String body = "{\"pickupLocation\":\"Bree\",\"destination\":\"Sandton\"}";

        String first = mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, u.getId()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, u.getId()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(bookings.findByCommuterIdOrderByIdDesc(u.getId())).hasSize(1);
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        User a = fixtures.commuter();
        User b = fixtures.commuter();
        String key = "shared-" + System.nanoTime();
        String body = "{\"pickupLocation\":\"Bree\",\"destination\":\"Sandton\"}";
        for (User u : new User[]{a, b}) {
            mvc.perform(post("/api/bookings/create").with(as(Role.COMMUTER, u.getId()))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.booking.commuterId").value(u.getId()));
        }
        assertThat(bookings.findByCommuterIdOrderByIdDesc(a.getId())).hasSize(1);
        assertThat(bookings.findByCommuterIdOrderByIdDesc(b.getId())).hasSize(1);
    }

    @Test
    void failedActionReleasesTheKey() throws Exception {
        User u = fixtures.commuterWithBalance("10.00");
        Driver d = fixtures.driver();
        Booking bk = fixtures.pendingBooking(u.getId());
        String key = "retry-" + System.nanoTime();

        // First try fails (fare above wallet balance)...
        mvc.perform(put("/api/bookings/" + bk.getId() + "/accept/" + d.getId()).param("fare", "50")
                        .header("Idempotency-Key", key).with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isConflict());
        // ...so the same key can be used again rather than replaying the failure or 409-ing forever.
        mvc.perform(put("/api/bookings/" + bk.getId() + "/accept/" + d.getId()).param("fare", "10")
                        .header("Idempotency-Key", key).with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }
}
