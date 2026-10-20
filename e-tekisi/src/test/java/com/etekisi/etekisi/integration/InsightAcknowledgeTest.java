package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.agent.insight.InsightCode;
import com.etekisi.etekisi.agent.insight.OperationalInsight;
import com.etekisi.etekisi.agent.insight.OperationalInsightRepository;
import com.etekisi.etekisi.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Operators acknowledge safety alerts so everyone can see someone is on it. */
@SpringBootTest
class InsightAcknowledgeTest {

    @Autowired private WebApplicationContext context;
    @Autowired private OperationalInsightRepository insights;

    private OperationalInsight panic() {
        OperationalInsight i = new OperationalInsight();
        i.setBookingId(770_001L);
        i.setType(OperationalInsight.Type.ALERT);
        i.setCode(InsightCode.SAFETY_PANIC);
        i.setSeverity(OperationalInsight.Severity.CRITICAL);
        i.setMessage("Panic button pressed");
        i.setCreatedAt(Instant.now());
        return insights.save(i);
    }

    @Test
    void operatorAcknowledgesOnce() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        OperationalInsight i = panic();
        int id = i.getId().intValue();

        mvc.perform(get("/api/insights").param("severity", "CRITICAL").param("acknowledged", "false")
                        .param("size", "200").with(as(Role.OPERATOR, 9300L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", hasItem(id)));

        mvc.perform(put("/api/insights/" + id + "/ack").with(as(Role.OPERATOR, 9300L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Called the commuter, they're safe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedBy").value(9300))
                .andExpect(jsonPath("$.acknowledgedNote").value("Called the commuter, they're safe"))
                .andExpect(jsonPath("$.acknowledgedAt").isNotEmpty());

        mvc.perform(put("/api/insights/" + id + "/ack").with(as(Role.ADMIN, 9301L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_ACKNOWLEDGED"));

        mvc.perform(get("/api/insights").param("acknowledged", "false").param("size", "200")
                        .with(as(Role.OPERATOR, 9300L)))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(id))));
    }

    @Test
    void onlyStaffCanAcknowledge() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        OperationalInsight i = panic();
        mvc.perform(put("/api/insights/" + i.getId() + "/ack").with(as(Role.DRIVER, 1L)))
                .andExpect(status().isForbidden());
    }
}
