package com.etekisi.etekisi.agent.safety;

import com.etekisi.etekisi.agent.audit.DecisionAuditLogger;
import com.etekisi.etekisi.agent.dto.SafetyAlert;
import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import com.etekisi.etekisi.agent.guardrail.PolicyEngine;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.model.Booking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyAgentTest {

    private TrustedDataService trusted;
    private SafetyAgent safety;

    @BeforeEach
    void setUp() {
        trusted = Mockito.mock(TrustedDataService.class);
        safety = new SafetyAgent(trusted, new AgentProperties(),
                new PolicyEngine(), Mockito.mock(DecisionAuditLogger.class));
        Mockito.when(trusted.getBooking(1L))
                .thenReturn(TrustedFact.of(booking(), "BookingRepository"));
    }

    @Test
    void insufficientWhenTooFewPings() {
        SafetyAlert alert = safety.evaluate(1L,
                List.of(ping(-26.2, 28.0, 0)),
                SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH, "corr");
        assertThat(alert.type()).isEqualTo(SafetyAlert.AlertType.INSUFFICIENT_DATA);
        assertThat(alert.recommendedAction()).isEqualTo(SafetyAlert.Action.NONE);
    }

    @Test
    void detectsProlongedStationary() {
        // 5 pings, ~5 minutes apart, within 80m of each other.
        List<SafetyAgent.GpsPing> pings = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pings.add(ping(-26.2000, 28.0470, i * 60));
        }
        SafetyAlert alert = safety.evaluate(1L, pings,
                SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH, "corr");
        assertThat(alert.type()).isEqualTo(SafetyAlert.AlertType.PROLONGED_STATIONARY);
        assertThat(alert.severity()).isEqualTo(SafetyAlert.Severity.WARN);
    }

    @Test
    void noAlertWhenMovingAlongPath() {
        List<SafetyAgent.GpsPing> pings = List.of(
                ping(-26.2000, 28.0470, 0),
                ping(-26.2050, 28.0500, 60),
                ping(-26.2100, 28.0530, 120),
                ping(-26.2150, 28.0560, 180),
                ping(-26.2200, 28.0590, 240));
        SafetyAlert alert = safety.evaluate(1L, pings,
                SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH, "corr");
        assertThat(alert.type()).isEqualTo(SafetyAlert.AlertType.NONE);
        assertThat(alert.severity()).isEqualTo(SafetyAlert.Severity.INFO);
    }

    @Test
    void panicAlwaysRoutesToHumanReview() {
        SafetyAlert alert = safety.panic(1L, "corr");
        assertThat(alert.severity()).isEqualTo(SafetyAlert.Severity.CRITICAL);
        // PolicyEngine downgrades any CRITICAL action to HUMAN_REVIEW_REQUIRED.
        assertThat(alert.recommendedAction()).isEqualTo(SafetyAlert.Action.HUMAN_REVIEW_REQUIRED);
    }

    private Booking booking() {
        Booking b = new Booking();
        b.setId(1L);
        b.setStatus("ACCEPTED");
        return b;
    }

    private SafetyAgent.GpsPing ping(double lat, double lng, long secondsFromBase) {
        return new SafetyAgent.GpsPing(lat, lng,
                Instant.parse("2026-01-01T08:00:00Z").plusSeconds(secondsFromBase));
    }
}
