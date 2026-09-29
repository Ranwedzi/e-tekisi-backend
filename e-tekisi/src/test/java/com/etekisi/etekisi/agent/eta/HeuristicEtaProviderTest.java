package com.etekisi.etekisi.agent.eta;

import com.etekisi.etekisi.agent.dto.EtaEstimate;
import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicEtaProviderTest {

    @Test
    void quotesPositiveSecondsForReasonableDistance() {
        HeuristicEtaProvider p = new HeuristicEtaProvider(new AgentProperties());
        Optional<RouteQuote> q = p.quote(-26.2008, 28.0440, -26.2685, 27.9395); // ~12 km
        assertThat(q).isPresent();
        RouteQuote rq = q.get();
        assertThat(rq.meanSeconds()).isPositive();
        assertThat(rq.p90Seconds()).isGreaterThanOrEqualTo(rq.meanSeconds());
        assertThat(rq.source()).isEqualTo(EtaEstimate.Source.HEURISTIC);
        assertThat(rq.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    void refusesToQuoteBeyondConfiguredCap() {
        HeuristicEtaProvider p = new HeuristicEtaProvider(new AgentProperties());
        // ~200 km north — beyond the default 80km cap
        Optional<RouteQuote> q = p.quote(-26.2, 28.0, -24.4, 28.0);
        assertThat(q).isEmpty();
    }

    @Test
    void priorityIsLowestSoOtherProvidersWin() {
        HeuristicEtaProvider p = new HeuristicEtaProvider(new AgentProperties());
        assertThat(p.priority()).isZero();
    }
}
