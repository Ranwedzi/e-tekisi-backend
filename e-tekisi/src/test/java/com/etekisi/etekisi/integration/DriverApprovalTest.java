package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.User;
import com.etekisi.etekisi.repository.DriverRepository;
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

/** New drivers can't take trips until an operator has checked and approved them. */
@SpringBootTest
class DriverApprovalTest {

    private static final long OPERATOR = 9100L;

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private DriverRepository drivers;

    private long registerDriver(MockMvc mvc, String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register/driver").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"New Driver","email":"%s","phoneNumber":"0820000777",
                                 "taxiPlate":"GP777NEW","licenseNumber":"LIC-777","password":"password123"}""".formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("PENDING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"userId\":(\\d+).*", "$1"));
    }

    @Test
    void pendingDriverIsBlockedUntilApproved() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String email = "new" + System.nanoTime() + "@test.dev";
        long id = registerDriver(mvc, email);

        mvc.perform(put("/api/auth/driver/" + id + "/status").param("active", "true").with(as(Role.DRIVER, id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRIVER_NOT_APPROVED"));
        mvc.perform(post("/api/queue/join").with(as(Role.DRIVER, id))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"taxiRank\":\"Bree\"}"))
                .andExpect(status().isConflict());
        User commuter = fixtures.commuter();
        Booking b = fixtures.pendingBooking(commuter.getId());
        // Even if the online flag were somehow set, offers are refused.
        Driver d = drivers.findById(id).orElseThrow();
        d.setActive(true);
        drivers.save(d);
        mvc.perform(put("/api/bookings/" + b.getId() + "/offer").param("fare", "30").with(as(Role.DRIVER, id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRIVER_NOT_APPROVED"));

        // The operator sees them in the review queue and approves.
        mvc.perform(get("/api/ops/drivers").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].licenseNumber").value("LIC-777"));
        mvc.perform(put("/api/ops/drivers/" + id + "/approve").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.approvalDecidedBy").value(OPERATOR));

        mvc.perform(put("/api/auth/driver/" + id + "/status").param("active", "true").with(as(Role.DRIVER, id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"));
        mvc.perform(put("/api/bookings/" + b.getId() + "/offer").param("fare", "30").with(as(Role.DRIVER, id)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectingTakesTheDriverOfflineAndExplainsWhy() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        Driver d = fixtures.driver(); // approved and online
        mvc.perform(put("/api/ops/drivers/" + d.getId() + "/reject").with(as(Role.ADMIN, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Operating permit expired\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("REJECTED"));
        assertThat(drivers.findById(d.getId()).orElseThrow().isActive()).isFalse();

        mvc.perform(put("/api/auth/driver/" + d.getId() + "/status").param("active", "true").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Your driver account was not approved: Operating permit expired"));
        mvc.perform(get("/api/auth/driver/" + d.getId()).with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$.approvalNote").value("Operating permit expired"));
    }

    @Test
    void onlyStaffCanReviewDrivers() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        Driver d = fixtures.driver();
        mvc.perform(get("/api/ops/drivers").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/ops/drivers/" + d.getId() + "/approve").with(as(Role.COMMUTER, 1L)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/ops/drivers/" + d.getId() + "/reject").with(as(Role.OPERATOR, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
