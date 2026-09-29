package com.etekisi.etekisi.agent.intent;

import com.etekisi.etekisi.agent.audit.DecisionAuditLogger;
import com.etekisi.etekisi.agent.dto.ReasonCode;
import com.etekisi.etekisi.agent.dto.TripIntentResult;
import com.etekisi.etekisi.agent.dto.TripIntentResult.RankCandidate;
import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import com.etekisi.etekisi.agent.guardrail.DecisionContext;
import com.etekisi.etekisi.agent.guardrail.TrustedDataService;
import com.etekisi.etekisi.agent.guardrail.TrustedFact;
import com.etekisi.etekisi.model.Route;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Trip Intent agent. Pipeline:
 *   1. Optional LLM call → produces ONLY candidate strings (origin/destination).
 *   2. {@link RankResolver} matches strings against the rank repository.
 *   3. Repository lookup confirms a Route exists between the resolved ranks.
 *
 * If any step fails, the result is NEEDS_CONFIRMATION or REJECTED — never
 * CONFIRMED. The LLM cannot create a rank, route, or fare. Confidence is
 * derived from the resolver match score, not from the LLM.
 */
@Service
public class TripIntentAgent {

    public static final String NAME = "TripIntentAgent";
    public static final String VERSION = "1.0.0";

    private final RankResolver resolver;
    private final TrustedDataService trusted;
    private final AgentProperties props;
    private final LlmIntentClient llm;
    private final DecisionAuditLogger audit;

    public TripIntentAgent(RankResolver resolver,
                           TrustedDataService trusted,
                           AgentProperties props,
                           LlmIntentClient llm,
                           DecisionAuditLogger audit) {
        this.resolver = resolver;
        this.trusted = trusted;
        this.props = props;
        this.llm = llm;
        this.audit = audit;
    }

    public TripIntentResult interpret(String rawText, String correlationId) {
        DecisionContext ctx = new DecisionContext(NAME, VERSION, correlationId);

        if (rawText == null || rawText.isBlank()) {
            ctx.addReason(ReasonCode.NO_DATA).withConfidence(0.0);
            return publish(reject(ctx));
        }

        String origin = null, destination = null;
        int party = 1;
        if (props.getIntent().isLlmEnabled()) {
            Optional<LlmIntentClient.RawIntent> parsed = safeExtract(rawText, ctx);
            if (parsed.isPresent()) {
                origin = parsed.get().originGuess();
                destination = parsed.get().destinationGuess();
                party = Math.max(1, parsed.get().partySize());
            }
        } else {
            ctx.addReason(ReasonCode.INTENT_LLM_DISABLED);
        }

        // Deterministic fallback: try the raw text as a single destination phrase.
        // The user app should ideally send structured fields; this only protects
        // against the raw-text path being unusable when LLM is off.
        if (origin == null && destination == null) {
            destination = rawText;
        }

        List<RankCandidate> originCands = resolver.resolve(origin, props.getIntent().getMinMatchScore(), 3);
        List<RankCandidate> destCands = resolver.resolve(destination, props.getIntent().getMinMatchScore(), 3);
        ctx.consulted("RankRepository");

        Long originId = topId(originCands);
        Long destId = topId(destCands);
        String originName = topName(originCands);
        String destName = topName(destCands);

        if (originId == null) ctx.addReason(ReasonCode.INTENT_RANK_UNRESOLVED);
        if (destId == null) ctx.addReason(ReasonCode.INTENT_RANK_UNRESOLVED);

        Long routeId = null;
        if (originName != null && destName != null) {
            TrustedFact<Route> route = trusted.resolveRoute(originName, destName);
            ctx.consulted(route);
            if (route.isPresent()) {
                routeId = route.value().getId();
            } else {
                ctx.addReason(ReasonCode.INTENT_ROUTE_UNRESOLVED);
            }
        }

        double topOrigin = topScore(originCands);
        double topDest = topScore(destCands);
        double confidence = (topOrigin + topDest) / 2.0;
        ctx.withConfidence(round(confidence));

        TripIntentResult.Status status;
        if (originId != null && destId != null && routeId != null && confidence >= 0.85) {
            status = TripIntentResult.Status.CONFIRMED;
            ctx.addReason(ReasonCode.INTENT_RESOLVED);
        } else if (!originCands.isEmpty() || !destCands.isEmpty()) {
            status = TripIntentResult.Status.NEEDS_CONFIRMATION;
            ctx.addReason(ReasonCode.INTENT_AMBIGUOUS);
        } else {
            status = TripIntentResult.Status.REJECTED;
            ctx.addReason(ReasonCode.NO_DATA).markFallback();
        }

        TripIntentResult result = new TripIntentResult(
                status, originId, originName, destId, destName,
                routeId, party, originCands, destCands, ctx.seal());
        return publish(result);
    }

    private Optional<LlmIntentClient.RawIntent> safeExtract(String text, DecisionContext ctx) {
        try {
            Optional<LlmIntentClient.RawIntent> r = llm.extract(text);
            if (r.isEmpty()) ctx.addReason(ReasonCode.INTENT_LLM_FAILURE);
            return r;
        } catch (RuntimeException e) {
            ctx.addReason(ReasonCode.INTENT_LLM_FAILURE).markDegraded();
            return Optional.empty();
        }
    }

    private TripIntentResult reject(DecisionContext ctx) {
        return new TripIntentResult(TripIntentResult.Status.REJECTED,
                null, null, null, null, null, 1,
                List.of(), List.of(), ctx.seal());
    }

    private TripIntentResult publish(TripIntentResult r) {
        audit.record(r.metadata(),
                "status=" + r.status() + " origin=" + r.originRankName() + " dest=" + r.destinationRankName(),
                r);
        return r;
    }

    private static Long topId(List<RankCandidate> cs) { return cs.isEmpty() ? null : cs.get(0).rankId(); }
    private static String topName(List<RankCandidate> cs) { return cs.isEmpty() ? null : cs.get(0).rankName(); }
    private static double topScore(List<RankCandidate> cs) { return cs.isEmpty() ? 0.0 : cs.get(0).matchScore(); }
    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
