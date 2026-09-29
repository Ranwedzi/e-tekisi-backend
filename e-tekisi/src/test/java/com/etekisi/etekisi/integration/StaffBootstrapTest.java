package com.etekisi.etekisi.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADMIN_BOOTSTRAP_* creates an operator who can log in and reach operator-only endpoints. */
@SpringBootTest(properties = {
        "tekisi.admin.bootstrap-email=Ops@Test.dev",
        "tekisi.admin.bootstrap-password=a-long-operator-password"
})
class StaffBootstrapTest {

    @Autowired private WebApplicationContext context;

    @Test
    void bootstrappedOperatorCanLogInAndUseOperatorEndpoints() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ops@test.dev\",\"password\":\"a-long-operator-password\",\"role\":\"OPERATOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/agents/hotspots").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // Staff can't sign in through the commuter door.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ops@test.dev\",\"password\":\"a-long-operator-password\",\"role\":\"COMMUTER\"}"))
                .andExpect(status().isUnauthorized());
    }
}
