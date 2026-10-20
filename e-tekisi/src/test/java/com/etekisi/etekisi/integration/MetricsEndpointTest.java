package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Prometheus scrapes /actuator/prometheus with Basic credentials; nobody else gets in. */
@SpringBootTest(properties = {
        "tekisi.metrics.username=prometheus",
        "tekisi.metrics.password=a-long-random-scrape-password",
        "management.endpoints.web.exposure.include=prometheus,metrics"
})
class MetricsEndpointTest {

    @Autowired private WebApplicationContext context;

    @Test
    void scraperCredentialsAndOperatorsCanReadMetrics() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        mvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", "a-long-random-scrape-password")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tekisi_safety_unacknowledged")));
        mvc.perform(get("/actuator/prometheus").with(as(Role.OPERATOR, 9400L)))
                .andExpect(status().isOk());
    }

    @Test
    void everyoneElseIsRefused() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", "wrong-password-wrong")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(as(Role.COMMUTER, 1L)))
                .andExpect(status().isForbidden());
        // The scrape credentials don't open anything else.
        mvc.perform(get("/actuator/metrics").with(httpBasic("prometheus", "a-long-random-scrape-password")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/insights").with(httpBasic("prometheus", "a-long-random-scrape-password")))
                .andExpect(status().isUnauthorized());
    }
}
