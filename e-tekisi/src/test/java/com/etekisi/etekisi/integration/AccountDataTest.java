package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.config.JwtTokenService;
import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.*;
import com.etekisi.etekisi.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** POPIA: people can download what we hold about them and delete their account. */
@SpringBootTest
class AccountDataTest {

    private static final String PASSWORD = "correct-horse-1";

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private UserRepository users;
    @Autowired private DriverRepository drivers;
    @Autowired private WalletRepository wallets;
    @Autowired private BookingRepository bookings;
    @Autowired private MessageRepository messages;
    @Autowired private NotificationRepository notifications;
    @Autowired private SavedRankRepository savedRanks;
    @Autowired private DriverLocationRepository locations;
    @Autowired private NotificationService notificationService;
    @Autowired private BCryptPasswordEncoder encoder;
    @Autowired private JwtTokenService tokens;

    private User commuterWithPassword() {
        User u = fixtures.commuter();
        u.setPassword(encoder.encode(PASSWORD));
        return users.save(u);
    }

    private Driver driverWithPassword() {
        Driver d = fixtures.driver();
        d.setPassword(encoder.encode(PASSWORD));
        return drivers.save(d);
    }

    private static String pw(String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    private Message message(long bookingId, long senderId, Role role, String text) {
        Message m = new Message();
        m.setBookingId(bookingId);
        m.setSenderId(senderId);
        m.setSenderRole(role);
        m.setContent(text);
        return messages.save(m);
    }

    @Test
    void commuterExportContainsTheirData() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User u = commuterWithPassword();
        Booking b = fixtures.pendingBooking(u.getId());
        message(b.getId(), u.getId(), Role.COMMUTER, "I'm at the Bree entrance");
        notificationService.send(u.getId(), Role.COMMUTER, "Welcome");
        SavedRank r = new SavedRank();
        r.setCommuterId(u.getId());
        r.setName("Home");
        r.setAddress("12 Vilakazi St");
        savedRanks.save(r);

        mvc.perform(get("/api/me/export").with(as(Role.COMMUTER, u.getId())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(jsonPath("$.role").value("COMMUTER"))
                .andExpect(jsonPath("$.profile.email").value(u.getEmail()))
                .andExpect(jsonPath("$.profile.password").doesNotExist())
                .andExpect(jsonPath("$.trips[0].id").value(b.getId()))
                .andExpect(jsonPath("$.messagesSent[0].content").value("I'm at the Bree entrance"))
                .andExpect(jsonPath("$.notifications[0].message").value("Welcome"))
                .andExpect(jsonPath("$.savedPlaces[0].address").value("12 Vilakazi St"));
    }

    @Test
    void commuterDeletionAnonymisesAndSignsOut() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User u = commuterWithPassword();
        String email = u.getEmail();
        Booking pending = fixtures.pendingBooking(u.getId());
        Message m = message(pending.getId(), u.getId(), Role.COMMUTER, "my number is 0711234567");
        notificationService.send(u.getId(), Role.COMMUTER, "Welcome");
        String realToken = tokens.mint(u.getId(), Role.COMMUTER.name());

        mvc.perform(post("/api/me/delete").with(as(Role.COMMUTER, u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(pw("wrong-password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_INCORRECT"));

        mvc.perform(post("/api/me/delete").with(as(Role.COMMUTER, u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(pw(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));

        User after = users.findById(u.getId()).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(after.getFullName()).isEqualTo("Deleted user");
        assertThat(after.getEmail()).endsWith("@deleted.invalid");
        assertThat(after.getPhoneNumber()).isEqualTo("0000000000");
        assertThat(messages.findById(m.getId()).orElseThrow().getContent()).isEqualTo("[message removed]");
        assertThat(notifications.findByUserIdAndRoleOrderByCreatedAtDesc(u.getId(), Role.COMMUTER)).isEmpty();
        // The trip row is kept as a record but no longer waits for a driver.
        assertThat(fixtures.status(pending.getId())).isEqualTo(BookingStatus.CANCELLED);

        // Their old token stops working straight away, and they can't sign in again.
        mvc.perform(get("/api/auth/commuter/" + u.getId()).header("Authorization", "Bearer " + realToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"COMMUTER\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherUsersRealTokenStillWorks() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User u = commuterWithPassword();
        mvc.perform(get("/api/auth/commuter/" + u.getId())
                        .header("Authorization", "Bearer " + tokens.mint(u.getId(), Role.COMMUTER.name())))
                .andExpect(status().isOk());
    }

    @Test
    void commuterDeletionRefusedWithOpenTripOrMoneyInWallet() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User u = commuterWithPassword();
        Booking b = fixtures.pendingBooking(u.getId());
        b.setStatus(BookingStatus.ACCEPTED);
        bookings.save(b);
        mvc.perform(post("/api/me/delete").with(as(Role.COMMUTER, u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(pw(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPEN_TRIP"));

        b.setStatus(BookingStatus.COMPLETED);
        bookings.save(b);
        Wallet w = new Wallet();
        w.setUserId(u.getId());
        w.setRole(Role.COMMUTER);
        w.setBalance(new BigDecimal("25.00"));
        w.setTotalTrips(1);
        wallets.save(w);
        mvc.perform(post("/api/me/delete").with(as(Role.COMMUTER, u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(pw(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_EMPTY"));
        assertThat(users.findById(u.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void driverDeletionNeedsZeroBalanceThenClearsLocation() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        Driver d = driverWithPassword();
        d.setBalance(new BigDecimal("80.00"));
        drivers.save(d);
        DriverLocation loc = new DriverLocation();
        loc.setDriverId(d.getId());
        loc.setLat(-26.2);
        loc.setLng(28.04);
        loc.setLastUpdated(LocalDateTime.now());
        locations.save(loc);

        mvc.perform(get("/api/me/export").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.taxiPlate").value("ABC123GP"))
                .andExpect(jsonPath("$.lastKnownLocation.lat").value(-26.2));

        mvc.perform(post("/api/me/delete").with(as(Role.DRIVER, d.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(pw(PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BALANCE_NOT_EMPTY"));

        d = drivers.findById(d.getId()).orElseThrow();
        d.setBalance(BigDecimal.ZERO);
        drivers.save(d);
        mvc.perform(post("/api/me/delete").with(as(Role.DRIVER, d.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(pw(PASSWORD)))
                .andExpect(status().isOk());

        Driver after = drivers.findById(d.getId()).orElseThrow();
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(after.isActive()).isFalse();
        assertThat(after.getTaxiPlate()).isEqualTo("DELETED");
        assertThat(after.getApprovalStatus()).isEqualTo(DriverApproval.REJECTED);
        assertThat(locations.findById(d.getId())).isEmpty();
    }

    @Test
    void staffCannotUseSelfServiceDeletion() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        mvc.perform(post("/api/me/delete").with(as(Role.OPERATOR, 9200L))
                        .contentType(MediaType.APPLICATION_JSON).content(pw(PASSWORD)))
                .andExpect(status().isForbidden());
    }
}
