package com.etekisi.etekisi.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Account lockout after repeated wrong passwords (the per-IP limit is relaxed in the test profile). */
@SpringBootTest
class LoginThrottleTest {

    @Autowired private WebApplicationContext context;

    private static String login(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"COMMUTER\"}";
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenForTheRightPassword() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String email = "lock" + System.nanoTime() + "@test.dev";
        mvc.perform(post("/api/auth/register/commuter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Lock Me\",\"email\":\"" + email + "\",\"phoneNumber\":\"0710000001\",\"password\":\"right-password\",\"acceptTerms\":true,\"termsVersion\":\"test-terms-v1\"}"))
                .andExpect(status().isOk());

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(email, "wrong")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(email, "right-password")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());

        // Other accounts are unaffected, and unknown emails lock the same way (no account enumeration).
        String ghost = "ghost" + System.nanoTime() + "@test.dev";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(ghost, "x")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(ghost, "x")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulLoginResetsTheCounter() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String email = "reset" + System.nanoTime() + "@test.dev";
        mvc.perform(post("/api/auth/register/commuter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Reset Me\",\"email\":\"" + email + "\",\"phoneNumber\":\"0710000001\",\"password\":\"right-password\",\"acceptTerms\":true,\"termsVersion\":\"test-terms-v1\"}"))
                .andExpect(status().isOk());
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(email, "wrong")))
                        .andExpect(status().isUnauthorized());
            }
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(email, "right-password")))
                    .andExpect(status().isOk());
        }
    }
}
