package com.etekisi.etekisi.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Per-IP cap on sign-in / sign-up requests. */
@SpringBootTest(properties = "tekisi.security.login.ip-requests-per-minute=3")
class IpRateLimitTest {

    @Autowired private WebApplicationContext context;

    @Test
    void fourthAuthRequestInAMinuteIsRefused() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String body = "{\"email\":\"nobody@test.dev\",\"password\":\"x\",\"role\":\"DRIVER\"}";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        // Sign-up shares the same budget.
        mvc.perform(post("/api/auth/register/commuter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Spam\",\"email\":\"spam@test.dev\",\"phoneNumber\":\"0710000001\",\"password\":\"password123\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
