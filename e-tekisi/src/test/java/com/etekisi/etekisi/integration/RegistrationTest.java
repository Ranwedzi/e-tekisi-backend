package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.User;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class RegistrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private UserRepository users;
    @Autowired private DriverRepository drivers;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = TestAuth.mvc(context);
    }

    @Test
    void signUpCannotOverwriteAnExistingAccount() throws Exception {
        User victim = fixtures.commuter();
        String victimEmail = victim.getEmail();
        String attackerEmail = "attacker" + System.nanoTime() + "@test.dev";

        mvc.perform(post("/api/auth/register/commuter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":%d,"fullName":"Mallory","email":"%s",
                                 "phoneNumber":"0710000001","password":"password123"}"""
                                .formatted(victim.getId(), attackerEmail)))
                .andExpect(status().isOk());

        User after = users.findById(victim.getId()).orElseThrow();
        assertThat(after.getEmail()).isEqualTo(victimEmail);
        assertThat(after.getFullName()).isEqualTo("Test Commuter");
        User created = users.findByEmailIgnoreCase(attackerEmail).orElseThrow();
        assertThat(created.getId()).isNotEqualTo(victim.getId());
    }

    @Test
    void driverSignUpIgnoresServerOwnedFieldsAndNormalisesPlate() throws Exception {
        String email = "Driver" + System.nanoTime() + "@Test.dev";
        mvc.perform(post("/api/auth/register/driver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Sipho","email":"%s","phoneNumber":"0820000001",
                                 "taxiPlate":"gp 123-abc","licenseNumber":"LIC-9","password":"password123",
                                 "balance":100000,"tripsCompleted":999,"active":true}"""
                                .formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DRIVER"));

        Driver d = drivers.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(d.getBalance()).isEqualByComparingTo("0");
        assertThat(d.getTripsCompleted()).isZero();
        assertThat(d.isActive()).isFalse();
        assertThat(d.getTaxiPlate()).isEqualTo("GP123ABC");
        assertThat(d.getEmail()).isEqualTo(email.toLowerCase());
    }

    @Test
    void driverSignUpWithoutLicenseIsA400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/register/driver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Sipho","email":"x%d@test.dev","phoneNumber":"0820000001",
                                 "taxiPlate":"GP123","password":"password123"}""".formatted(System.nanoTime())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.licenseNumber").exists());
    }

    @Test
    void duplicateEmailIsA409() throws Exception {
        User existing = fixtures.commuter();
        mvc.perform(post("/api/auth/register/commuter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Copy","email":"%s","phoneNumber":"0710000001","password":"password123"}"""
                                .formatted(existing.getEmail().toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void loginIssuesATokenThatTheApiAccepts() throws Exception {
        String email = "login" + System.nanoTime() + "@test.dev";
        mvc.perform(post("/api/auth/register/commuter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Lindi","email":"%s","phoneNumber":"0710000001","password":"password123"}"""
                                .formatted(email)))
                .andExpect(status().isOk());

        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password123","role":"COMMUTER"}""".formatted(email.toUpperCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        long userId = Long.parseLong(body.replaceAll(".*\"userId\":(\\d+).*", "$1"));

        mvc.perform(get("/api/auth/commuter/" + userId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/auth/commuter/" + userId).header("Authorization", "Bearer " + token + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
        User u = fixtures.commuter();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"nope\",\"role\":\"COMMUTER\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@test.dev\",\"password\":\"nope\",\"role\":\"COMMUTER\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void completeTripShortcutIsGone() throws Exception {
        Driver d = fixtures.driver();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/auth/driver/" + d.getId() + "/complete-trip").param("amount", "1000")
                        .with(TestAuth.as(com.etekisi.etekisi.model.Role.DRIVER, d.getId())))
                .andExpect(status().is4xxClientError());
        assertThat(fixtures.driverBalance(d.getId())).isEqualByComparingTo("0");
    }
}
