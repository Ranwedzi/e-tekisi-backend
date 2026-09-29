package com.etekisi.etekisi.agent.eta;

import com.etekisi.etekisi.agent.audit.DecisionAuditLogger;
import com.etekisi.etekisi.agent.dto.EtaEstimate;
import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.model.DriverLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EtaServiceTest {

    private TrustedDataService trusted;
    private EtaService eta;

    @BeforeEach
    void setUp() {
        trusted = Mockito.mock(TrustedDataService.class);
        AgentProperties props = new AgentProperties();
        // Always-on heuristic provider — tests assert deterministic fallback path.
        eta = new EtaService(trusted, props, Mockito.mock(DecisionAuditLogger.class),
                java.util.List.of(new HeuristicEtaProvider(props)));
    }

    @Test
    void unknownWhenDriverLocationMissing() {
        Mockito.when(trusted.getFreshDriverLocation(7L))
                .thenReturn(TrustedFact.missing("DriverLocationRepository"));
        EtaEstimate e = eta.estimatePickupEta(1L, 7L, -26.2, 28.0, "corr");
        assertThat(e.status()).isEqualTo(EtaEstimate.Status.UNKNOWN);
        assertThat(e.meanSeconds()).isNull();
        assertThat(e.metadata().degraded()).isTrue();
        assertThat(e.metadata().fallbackUsed()).isTrue();
    }

    @Test
    void outOfRangeWhenTooFar() {
        Mockito.when(trusted.getFreshDriverLocation(7L))
                .thenReturn(TrustedFact.of(loc(-26.2, 28.0), "DriverLocationRepository"));
        // 200km north — well past the 80km cap
        EtaEstimate e = eta.estimatePickupEta(1L, 7L, -24.4, 28.0, "corr");
        assertThat(e.status()).isEqualTo(EtaEstimate.Status.OUT_OF_RANGE);
        assertThat(e.meanSeconds()).isNull();
    }

    @Test
    void estimatesPositiveSecondsForReasonableDistance() {
        Mockito.when(trusted.getFreshDriverLocation(7L))
                .thenReturn(TrustedFact.of(loc(-26.2008, 28.0440), "DriverLocationRepository"));
        EtaEstimate e = eta.estimatePickupEta(1L, 7L, -26.2685, 27.9395, "corr"); // ~12 km
        assertThat(e.status()).isEqualTo(EtaEstimate.Status.OK);
        assertThat(e.meanSeconds()).isPositive();
        assertThat(e.p90Seconds()).isGreaterThan(e.meanSeconds());
        assertThat(e.source()).isEqualTo(EtaEstimate.Source.HEURISTIC);
    }

    @Test
    void failingHigherPriorityProviderDegradesToHeuristic() {
        // A higher-priority routing provider that always returns empty should
        // cause EtaService to fall back to the heuristic AND record the
        // ETA_ROUTING_FAILED reason so audits show the fallback was taken.
        AgentProperties props = new AgentProperties();
        EtaProvider failingRouting = new EtaProvider() {
            @Override public String name() { return "fake-routing"; }
            @Override public int priority() { return 100; }
            @Override public java.util.Optional<RouteQuote> quote(double oLat, double oLng,
                                                                  double dLat, double dLng) {
                return java.util.Optional.empty();
            }
        };
        EtaService chain = new EtaService(trusted, props, Mockito.mock(DecisionAuditLogger.class),
                java.util.List.of(failingRouting, new HeuristicEtaProvider(props)));
        Mockito.when(trusted.getFreshDriverLocation(7L))
                .thenReturn(TrustedFact.of(loc(-26.2008, 28.0440), "DriverLocationRepository"));

        EtaEstimate e = chain.estimatePickupEta(1L, 7L, -26.2685, 27.9395, "corr");

        assertThat(e.status()).isEqualTo(EtaEstimate.Status.OK);
        assertThat(e.source()).isEqualTo(EtaEstimate.Source.HEURISTIC);
        assertThat(e.metadata().fallbackUsed()).isTrue();
        assertThat(e.metadata().reasonCodes())
                .contains(com.etekisi.etekisi.agent.dto.ReasonCode.ETA_ROUTING_FAILED);
    }

    private DriverLocation loc(double lat, double lng) {
        DriverLocation l = new DriverLocation();
        l.setDriverId(7L);
        l.setLat(lat);
        l.setLng(lng);
        l.setLastUpdated(LocalDateTime.now());
        return l;
    }
}
