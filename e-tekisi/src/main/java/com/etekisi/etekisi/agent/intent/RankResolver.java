package com.etekisi.etekisi.agent.intent;

import com.etekisi.etekisi.agent.dto.TripIntentResult.RankCandidate;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.model.TaxiRank;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Deterministic resolver from a free-text candidate name to a real
 * {@link TaxiRank} row. Uses a normalised form + alias table + simple
 * Levenshtein-bounded fuzzy match. Never returns a rank that does not
 * exist in {@link TrustedDataService#getAllRanks()}.
 *
 * The alias map is intentionally tiny and local to this file. Production
 * should externalise it to a config table — but the resolver guarantees
 * that no LLM-emitted string ever becomes an authoritative rankId without
 * passing through here.
 */
@Component
public class RankResolver {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("bara", "baragwanath"),
            Map.entry("baragwaneth", "baragwanath"),
            Map.entry("noord", "noord taxi rank"),
            Map.entry("mtn rank", "mtn taxi rank"),
            Map.entry("park station", "park station"),
            Map.entry("jhb cbd", "johannesburg cbd"),
            Map.entry("joburg cbd", "johannesburg cbd")
    );

    private final TrustedDataService trusted;

    public RankResolver(TrustedDataService trusted) { this.trusted = trusted; }

    public List<RankCandidate> resolve(String raw, double minScore, int limit) {
        if (raw == null || raw.isBlank()) return List.of();
        String normalized = normalize(ALIASES.getOrDefault(raw.trim().toLowerCase(Locale.ROOT), raw));
        TrustedFact<List<TaxiRank>> ranksFact = trusted.getAllRanks();
        if (!ranksFact.isPresent()) return List.of();

        List<RankCandidate> scored = new ArrayList<>();
        for (TaxiRank r : ranksFact.value()) {
            if (r.getName() == null) continue;
            double s = score(normalized, normalize(r.getName()));
            if (s >= minScore) {
                scored.add(new RankCandidate(r.getId(), r.getName(), round(s)));
            }
        }
        scored.sort(Comparator.comparingDouble(RankCandidate::matchScore).reversed());
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Token-overlap + bounded Levenshtein → 0..1. Conservative and explainable. */
    private static double score(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (b.contains(a) || a.contains(b)) return 0.9;
        Set<String> ta = new HashSet<>(Arrays.asList(a.split(" ")));
        Set<String> tb = new HashSet<>(Arrays.asList(b.split(" ")));
        Set<String> intersect = new HashSet<>(ta);
        intersect.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        double jaccard = union.isEmpty() ? 0.0 : (double) intersect.size() / union.size();
        int lev = levenshtein(a, b, 8);
        double levScore = lev < 0 ? 0.0 : Math.max(0.0, 1.0 - lev / Math.max(1.0, Math.max(a.length(), b.length())));
        return Math.max(jaccard, levScore);
    }

    private static int levenshtein(String a, String b, int max) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > max) return -1;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (curr[j] < rowMin) rowMin = curr[j];
            }
            if (rowMin > max) return -1;
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
