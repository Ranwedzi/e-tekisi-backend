package com.etekisi.etekisi.agent.intent;

import com.etekisi.etekisi.agent.dto.TripIntentResult.RankCandidate;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.model.TaxiRank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RankResolverTest {

    private TrustedDataService trusted;
    private RankResolver resolver;

    @BeforeEach
    void setUp() {
        trusted = Mockito.mock(TrustedDataService.class);
        Mockito.when(trusted.getAllRanks()).thenReturn(
                TrustedFact.of(List.of(
                        rank(1L, "Baragwanath Taxi Rank"),
                        rank(2L, "Noord Taxi Rank"),
                        rank(3L, "Park Station"),
                        rank(4L, "MTN Taxi Rank")
                ), "RankRepository"));
        resolver = new RankResolver(trusted);
    }

    @Test
    void resolvesAliasBaraToBaragwanath() {
        List<RankCandidate> hits = resolver.resolve("Bara", 0.5, 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).rankId()).isEqualTo(1L);
    }

    @Test
    void resolvesExactNameWithHighScore() {
        List<RankCandidate> hits = resolver.resolve("Park Station", 0.5, 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).rankId()).isEqualTo(3L);
        assertThat(hits.get(0).matchScore()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void rejectsCompletelyUnknown() {
        List<RankCandidate> hits = resolver.resolve("Atlantis Quantum Hub", 0.65, 3);
        assertThat(hits).isEmpty();
    }

    @Test
    void emptyInputReturnsNothing() {
        assertThat(resolver.resolve("", 0.5, 3)).isEmpty();
        assertThat(resolver.resolve(null, 0.5, 3)).isEmpty();
    }

    @Test
    void resolverNeverInventsRanks() {
        // Even a partial match must point at one of the four ranks above.
        List<RankCandidate> hits = resolver.resolve("noord", 0.5, 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(c -> assertThat(c.rankId()).isIn(1L, 2L, 3L, 4L));
    }

    private static TaxiRank rank(Long id, String name) {
        TaxiRank r = new TaxiRank();
        r.setId(id);
        r.setName(name);
        return r;
    }
}
