package com.etekisi.etekisi.agent.insight;

import com.etekisi.etekisi.agent.event.TripCompletedEvent;
import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.agent.trip.TripGpsPing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

class TripAnomalyAgentTest {

    private TrustedDataService trusted;
    private AgentProperties props;
    private TripAnomalyAgent agent;

    @BeforeEach
    void setUp() {
        trusted = Mockito.mock(TrustedDataService.class);
        props = new AgentProperties();
        agent = new TripAnomalyAgent(trusted, props);
    }

    @Test
    void emitsBaselineTripCompletedMetricEvenForCleanTrip() {
        Mockito.when(trusted.getRecentTripPings(anyLong(), anyInt()))
                .thenReturn(TrustedFact.of(buildPings(20), "TripGpsPingRepository"));
        TripCompletedEvent e = event(50L, 7L, 100L, 100.0, null);

        List<OperationalInsight> insights = agent.inspectCompletedTrip(e);

        assertThat(insights).extracting(OperationalInsight::getCode)
                .contains(InsightCode.TRIP_COMPLETED_OK);
    }

    @Test
    void flagsZeroFareTrip() {
        Mockito.when(trusted.getRecentTripPings(anyLong(), anyInt()))
                .thenReturn(TrustedFact.of(buildPings(20), "TripGpsPingRepository"));
        TripCompletedEvent e = event(50L, 7L, 100L, 0.0, null);

        List<OperationalInsight> insights = agent.inspectCompletedTrip(e);

        assertThat(insights).anySatisfy(i -> {
            assertThat(i.getCode()).isEqualTo(InsightCode.ZERO_FARE_TRIP);
            assertThat(i.getSeverity()).isEqualTo(OperationalInsight.Severity.WARN);
        });
    }

    @Test
    void flagsHighFareAboveThreshold() {
        props.getInsight().setHighFareRand(400.0);
        Mockito.when(trusted.getRecentTripPings(anyLong(), anyInt()))
                .thenReturn(TrustedFact.of(buildPings(20), "TripGpsPingRepository"));
        TripCompletedEvent e = event(51L, 7L, 100L, 999.0, null);

        List<OperationalInsight> insights = agent.inspectCompletedTrip(e);

        assertThat(insights).anySatisfy(i -> {
            assertThat(i.getCode()).isEqualTo(InsightCode.UNUSUAL_HIGH_FARE);
            assertThat(i.getSeverity()).isEqualTo(OperationalInsight.Severity.WARN);
        });
    }

    @Test
    void flagsTripsWithInsufficientGpsHistory() {
        // Simulate a trip with only 1 ping recorded — operations need to know.
        Mockito.when(trusted.getRecentTripPings(anyLong(), anyInt()))
                .thenReturn(TrustedFact.of(buildPings(1), "TripGpsPingRepository"));
        TripCompletedEvent e = event(52L, 7L, 100L, 80.0, null);

        List<OperationalInsight> insights = agent.inspectCompletedTrip(e);

        assertThat(insights).anySatisfy(i -> {
            assertThat(i.getCode()).isEqualTo(InsightCode.NO_GPS_HISTORY);
            assertThat(i.getSeverity()).isEqualTo(OperationalInsight.Severity.WARN);
        });
    }

    @Test
    void nullEventReturnsEmptyAndNeverThrows() {
        List<OperationalInsight> insights = agent.inspectCompletedTrip(null);
        assertThat(insights).isEmpty();
    }

    private static TripCompletedEvent event(Long bookingId, Long driverId, Long commuterId,
                                            Double fare, Double tip) {
        return new TripCompletedEvent(bookingId, driverId, commuterId, fare, tip,
                Instant.now(), "corr-" + bookingId);
    }

    private static List<TripGpsPing> buildPings(int n) {
        List<TripGpsPing> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TripGpsPing p = new TripGpsPing();
            p.setLat(-26.2);
            p.setLng(28.0);
            p.setRecordedAt(Instant.parse("2026-01-01T08:00:00Z").plusSeconds(i * 30L));
            p.setSource("DRIVER_APP");
            out.add(p);
        }
        return out;
    }
}
