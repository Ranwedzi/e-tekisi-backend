package com.etekisi.etekisi.agent.insight;

import com.etekisi.etekisi.agent.dto.DecisionMetadata;
import com.etekisi.etekisi.agent.dto.ReasonCode;
import com.etekisi.etekisi.agent.dto.SafetyAlert;
import com.etekisi.etekisi.agent.event.SafetyIncidentDetectedEvent;
import com.etekisi.etekisi.agent.event.TripCompletedEvent;
import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalInsightListenerTest {

    private OperationalInsightRepository repo;
    private TrustedDataService trusted;
    private OperationalInsightListener listener;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(OperationalInsightRepository.class);
        trusted = Mockito.mock(TrustedDataService.class);
        TripAnomalyAgent agent = new TripAnomalyAgent(trusted, new AgentProperties());
        listener = new OperationalInsightListener(agent, repo);
    }

    @Test
    void persistsInsightsForCompletedTrip() {
        Mockito.when(trusted.getRecentTripPings(Mockito.anyLong(), Mockito.anyInt()))
                .thenReturn(TrustedFact.of(List.of(), "TripGpsPingRepository"));
        TripCompletedEvent e = new TripCompletedEvent(
                10L, 7L, 100L, 50.0, null, Instant.now(), "corr-x");

        listener.onTripCompleted(e);

        ArgumentCaptor<OperationalInsight> captor = ArgumentCaptor.forClass(OperationalInsight.class);
        Mockito.verify(repo, Mockito.atLeast(2)).save(captor.capture());
        // Baseline metric is always present
        assertThat(captor.getAllValues())
                .extracting(OperationalInsight::getCode)
                .contains(InsightCode.TRIP_COMPLETED_OK, InsightCode.NO_GPS_HISTORY);
    }

    @Test
    void projectsSafetyIncidentToBoundedInsight() {
        SafetyAlert alert = new SafetyAlert(
                42L,
                SafetyAlert.Severity.CRITICAL,
                SafetyAlert.AlertType.PANIC_BUTTON,
                SafetyAlert.Action.HUMAN_REVIEW_REQUIRED,
                DecisionMetadata.of("SafetyAgent", "1.0", 1L, 1.0,
                        List.of(ReasonCode.PANIC_TRIGGERED), List.of("manual"),
                        false, false, "corr-y"));
        SafetyIncidentDetectedEvent e = new SafetyIncidentDetectedEvent(alert, Instant.now(), "corr-y");

        listener.onSafetyIncidentDetected(e);

        ArgumentCaptor<OperationalInsight> captor = ArgumentCaptor.forClass(OperationalInsight.class);
        Mockito.verify(repo).save(captor.capture());
        OperationalInsight i = captor.getValue();
        assertThat(i.getCode()).isEqualTo(InsightCode.SAFETY_PANIC);
        assertThat(i.getSeverity()).isEqualTo(OperationalInsight.Severity.CRITICAL);
        assertThat(i.getBookingId()).isEqualTo(42L);
        assertThat(i.getCorrelationId()).isEqualTo("corr-y");
        // Bounded message contract — no free-form / LLM text.
        assertThat(i.getMessage()).contains("PANIC_BUTTON").contains("CRITICAL");
    }

    @Test
    void persistFailureDoesNotCrashListener() {
        Mockito.when(trusted.getRecentTripPings(Mockito.anyLong(), Mockito.anyInt()))
                .thenReturn(TrustedFact.of(List.of(), "TripGpsPingRepository"));
        Mockito.when(repo.save(Mockito.any())).thenThrow(new RuntimeException("DB down"));

        TripCompletedEvent e = new TripCompletedEvent(
                11L, 7L, 100L, 50.0, null, Instant.now(), "corr-z");

        // Listener must absorb persistence failures so a single bad row does
        // not crash the whole agent pipeline.
        listener.onTripCompleted(e);
    }
}
