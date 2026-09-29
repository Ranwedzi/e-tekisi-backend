package com.etekisi.etekisi.agent.eta;

import com.etekisi.etekisi.agent.dto.EtaEstimate;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalRoutingEtaProviderTest {

    private final RoutingProperties cfg = enabled("https://routing.example/api");
    private final ExternalRoutingEtaProvider provider = new ExternalRoutingEtaProvider(cfg);

    @Test
    void parsesValidOsrmResponse() {
        String body = """
                {
                  "code": "Ok",
                  "routes": [
                    {"duration": 600.0, "distance": 5000.0}
                  ]
                }
                """;
        Optional<RouteQuote> q = provider.parse(body);
        assertThat(q).isPresent();
        RouteQuote rq = q.get();
        assertThat(rq.meanSeconds()).isEqualTo(600);
        assertThat(rq.p90Seconds()).isEqualTo(750); // 25% buffer
        assertThat(rq.distanceKm()).isEqualTo(5.0);
        assertThat(rq.source()).isEqualTo(EtaEstimate.Source.ROUTING_API);
    }

    @Test
    void returnsEmptyForBlankBody() {
        assertThat(provider.parse("")).isEmpty();
        assertThat(provider.parse(null)).isEmpty();
    }

    @Test
    void returnsEmptyForResponseWithoutRoutes() {
        Optional<RouteQuote> q = provider.parse("{\"code\": \"NoRoute\", \"routes\": []}");
        assertThat(q).isEmpty();
    }

    @Test
    void returnsEmptyForMalformedJson() {
        Optional<RouteQuote> q = provider.parse("{not json");
        assertThat(q).isEmpty();
    }

    @Test
    void returnsEmptyForNonPositiveDuration() {
        // Anti-hallucination: never accept a 0s ETA from the routing layer.
        Optional<RouteQuote> q = provider.parse("{\"routes\":[{\"duration\":0,\"distance\":100}]}");
        assertThat(q).isEmpty();
    }

    @Test
    void quoteReturnsEmptyWhenBaseUrlIsBlank() {
        // Misconfiguration must degrade silently — no exceptions thrown out.
        ExternalRoutingEtaProvider p = new ExternalRoutingEtaProvider(enabled(""));
        assertThat(p.quote(-26.2, 28.0, -26.3, 28.1)).isEmpty();
    }

    private static RoutingProperties enabled(String baseUrl) {
        RoutingProperties p = new RoutingProperties();
        p.setEnabled(true);
        p.setBaseUrl(baseUrl);
        return p;
    }
}
