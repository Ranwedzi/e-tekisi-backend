package com.etekisi.etekisi.agent.intent;

import com.etekisi.etekisi.agent.audit.DecisionAuditLogger;
import com.etekisi.etekisi.agent.dto.TripIntentResult;
import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.model.Route;
import com.etekisi.etekisi.model.TaxiRank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

class TripIntentAgentTest {

    private TrustedDataService trusted;
    private RankResolver resolver;
    private TripIntentAgent agent;

    @BeforeEach
    void setUp() {
        trusted = Mockito.mock(TrustedDataService.class);
        Mockito.when(trusted.getAllRanks()).thenReturn(
                TrustedFact.of(List.of(
                        rank(1L, "Park Station"),
                        rank(2L, "Baragwanath Taxi Rank")), "RankRepository"));
        resolver = new RankResolver(trusted);
        AgentProperties props = new AgentProperties();
        // LLM disabled in default config — agent must work deterministically.
        agent = new TripIntentAgent(resolver, trusted, props,
                userText -> Optional.empty(),
                Mockito.mock(DecisionAuditLogger.class));
    }

    @Test
    void rejectsNullInput() {
        TripIntentResult r = agent.interpret(null, "corr");
        assertThat(r.status()).isEqualTo(TripIntentResult.Status.REJECTED);
    }

    @Test
    void needsConfirmationWhenOnlyOneSideResolves() {
        Mockito.when(trusted.resolveRoute(anyString(), anyString()))
                .thenReturn(TrustedFact.missing("RouteRepository"));
        TripIntentResult r = agent.interpret("Park Station", "corr");
        // No origin parsed; only destination resolves to Park Station.
        assertThat(r.status()).isIn(
                TripIntentResult.Status.NEEDS_CONFIRMATION,
                TripIntentResult.Status.REJECTED);
        // The agent must not invent IDs it didn't resolve.
        assertThat(r.routeId()).isNull();
    }

    @Test
    void confirmsWhenBothRanksAndRouteResolve() {
        Route route = new Route();
        route.setId(42L);
        Mockito.when(trusted.resolveRoute(anyString(), anyString()))
                .thenReturn(TrustedFact.of(route, "RouteRepository"));

        // Provide an LLM that gives both sides cleanly.
        AgentProperties props = new AgentProperties();
        props.getIntent().setLlmEnabled(true);
        TripIntentAgent agentWithLlm = new TripIntentAgent(resolver, trusted, props,
                userText -> Optional.of(new LlmIntentClient.RawIntent(
                        "Park Station", "Baragwanath", 1)),
                Mockito.mock(DecisionAuditLogger.class));

        TripIntentResult r = agentWithLlm.interpret("Park Station to Bara please", "corr");
        assertThat(r.status()).isEqualTo(TripIntentResult.Status.CONFIRMED);
        assertThat(r.originRankId()).isEqualTo(1L);
        assertThat(r.destinationRankId()).isEqualTo(2L);
        assertThat(r.routeId()).isEqualTo(42L);
    }

    @Test
    void llmFailureDegradesToFallbackNotInvention() {
        AgentProperties props = new AgentProperties();
        props.getIntent().setLlmEnabled(true);
        TripIntentAgent agentWithLlm = new TripIntentAgent(resolver, trusted, props,
                userText -> { throw new RuntimeException("LLM down"); },
                Mockito.mock(DecisionAuditLogger.class));

        TripIntentResult r = agentWithLlm.interpret("anything", "corr");
        assertThat(r.metadata().degraded()).isTrue();
        // Must not fabricate confirmed IDs from a failed LLM.
        assertThat(r.status()).isNotEqualTo(TripIntentResult.Status.CONFIRMED);
    }

    private TaxiRank rank(Long id, String name) {
        TaxiRank r = new TaxiRank();
        r.setId(id);
        r.setName(name);
        return r;
    }
}
