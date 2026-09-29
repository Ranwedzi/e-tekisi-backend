package com.etekisi.etekisi.agent.eta;

import com.etekisi.etekisi.agent.audit.DecisionAuditLogger;
import com.etekisi.etekisi.agent.dto.EtaEstimate;
import com.etekisi.etekisi.agent.dto.ReasonCode;
import com.etekisi.etekisi.agent.guardrail.*;
import com.etekisi.etekisi.config.SloMetrics;
import com.etekisi.etekisi.model.DriverLocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * ETA service.
 *
 * Architecture:
 *   - delegates the actual time/distance calculation to an ordered chain of
 *     {@link EtaProvider}s (highest priority first).
 *   - heuristic provider is the always-on fallback.
 *   - if every provider returns Optional.empty() the service answers UNKNOWN
 *     — never invents a value.
 *
 * Anti-hallucination posture preserved end-to-end:
 *   - missing fresh driver location =&gt; UNKNOWN, degraded, fallbackUsed
 *   - distance &gt; cap =&gt; OUT_OF_RANGE
 *   - external routing failure =&gt; ETA_ROUTING_FAILED reason, transparent
 *     fallback to heuristic
 *   - confidence and reason codes always populated and bounded
 */
@Service
public class EtaService {

    public static final String NAME = "EtaService";
    public static final String VERSION = "2.0.0-provider-chain";

    private final TrustedDataService trusted;
    private final AgentProperties props;
    private final DecisionAuditLogger audit;
    private final List<EtaProvider> providers;

    // Field-injected so the existing 4-arg constructor used by EtaServiceTest
    // does not break. ObjectProvider keeps it null-safe when no MeterRegistry
    // is on the classpath (test profile).
    @Autowired(required = false)
    private ObjectProvider<SloMetrics> sloProvider;

    public EtaService(TrustedDataService trusted,
                      AgentProperties props,
                      DecisionAuditLogger audit,
                      List<EtaProvider> providers) {
        this.trusted = trusted;
        this.props = props;
        this.audit = audit;
        // Highest priority first — heuristic falls to the bottom by virtue of
        // its priority(). Defensive copy + sort so callers can't mutate.
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(EtaProvider::priority).reversed())
                .toList();
    }

    public EtaEstimate estimatePickupEta(Long bookingId,
                                         Long driverId,
                                         double commuterLat,
                                         double commuterLng,
                                         String correlationId) {
        DecisionContext ctx = new DecisionContext(NAME, VERSION, correlationId);

        TrustedFact<DriverLocation> loc = trusted.getFreshDriverLocation(driverId);
        ctx.consulted(loc);
        if (!loc.isPresent()) {
            ctx.addReason(ReasonCode.STALE_DATA).markFallback().markDegraded().withConfidence(0.0);
            EtaEstimate e = new EtaEstimate(bookingId, driverId, null, null,
                    EtaEstimate.Source.NONE, EtaEstimate.Status.UNKNOWN, ctx.seal());
            audit.record(e.metadata(), "bookingId=" + bookingId + " driverId=" + driverId, e);
            return e;
        }

        double meters = Geo.haversineMeters(commuterLat, commuterLng,
                loc.value().getLat(), loc.value().getLng());
        double km = meters / 1000.0;
        if (km > props.getEta().getMaxKm()) {
            ctx.addReason(ReasonCode.ETA_OUT_OF_RANGE).withConfidence(0.0);
            EtaEstimate e = new EtaEstimate(bookingId, driverId, null, null,
                    EtaEstimate.Source.NONE, EtaEstimate.Status.OUT_OF_RANGE, ctx.seal());
            audit.record(e.metadata(), "km=" + km, e);
            return e;
        }

        // Walk the provider chain. The first provider that returns a quote wins.
        // Any failures along the way are recorded as reason codes so the audit
        // trail can show which path was attempted.
        Optional<RouteQuote> quote = Optional.empty();
        for (EtaProvider provider : providers) {
            try {
                Optional<RouteQuote> q = provider.quote(loc.value().getLat(), loc.value().getLng(),
                        commuterLat, commuterLng);
                if (q.isPresent()) {
                    quote = q;
                    break;
                } else if (provider.priority() > 0) {
                    // External provider declined — record an explicit fallback reason
                    // so the operator dashboard sees we degraded.
                    ctx.addReason(ReasonCode.ETA_ROUTING_FAILED).markFallback();
                    emitFallbackMetric();
                }
            } catch (RuntimeException ex) {
                // Defensive: providers should never throw, but if they do we
                // do not let the exception poison the request.
                ctx.addReason(ReasonCode.ETA_ROUTING_FAILED).markFallback();
                emitFallbackMetric();
            }
        }

        if (quote.isEmpty()) {
            ctx.addReason(ReasonCode.NO_DATA).markFallback().withConfidence(0.0);
            EtaEstimate e = new EtaEstimate(bookingId, driverId, null, null,
                    EtaEstimate.Source.NONE, EtaEstimate.Status.UNKNOWN, ctx.seal());
            audit.record(e.metadata(), "bookingId=" + bookingId + " driverId=" + driverId, e);
            return e;
        }

        RouteQuote rq = quote.get();
        switch (rq.source()) {
            case ROUTING_API -> ctx.addReason(ReasonCode.ETA_ROUTING_API);
            case MODEL -> ctx.addReason(ReasonCode.ETA_MODEL);
            case HEURISTIC -> ctx.addReason(ReasonCode.ETA_HEURISTIC);
            case NONE -> { /* not reachable for a present quote */ }
        }
        ctx.withConfidence(rq.confidence());

        EtaEstimate estimate = new EtaEstimate(bookingId, driverId,
                rq.meanSeconds(), rq.p90Seconds(),
                rq.source(), EtaEstimate.Status.OK, ctx.seal());
        audit.record(estimate.metadata(),
                "bookingId=" + bookingId + " driverId=" + driverId
                        + " km=" + rq.distanceKm() + " provider=" + rq.providerName(),
                estimate);
        return estimate;
    }

    private void emitFallbackMetric() {
        if (sloProvider == null) return;
        SloMetrics m = sloProvider.getIfAvailable();
        if (m != null) m.etaFallbackUsed();
    }
}
