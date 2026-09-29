package com.etekisi.etekisi.agent.controller;

import com.etekisi.etekisi.agent.dispatch.DispatchAgent;
import com.etekisi.etekisi.agent.dispatch.DispatchRecommendation;
import com.etekisi.etekisi.agent.dispatch.DispatchRecommendationRepository;
import com.etekisi.etekisi.agent.dto.*;
import com.etekisi.etekisi.agent.eta.EtaService;
import com.etekisi.etekisi.agent.event.AgentEventPublisher;
import com.etekisi.etekisi.agent.event.SafetyIncidentDetectedEvent;
import com.etekisi.etekisi.agent.guardrail.PolicyEngine;
import com.etekisi.etekisi.agent.hotspot.HotspotService;
import com.etekisi.etekisi.agent.intent.TripIntentAgent;
import com.etekisi.etekisi.agent.observability.CorrelationContext;
import com.etekisi.etekisi.agent.safety.SafetyAgent;
import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.BookingStatus;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.BookingRepository;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.BookingService;
import com.etekisi.etekisi.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single REST surface for the Tekisi agent layer.
 * Each endpoint returns a bounded DTO carrying its own DecisionMetadata,
 * so clients always see confidence and reason codes alongside the answer.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final DispatchAgent dispatch;
    private final EtaService eta;
    private final SafetyAgent safety;
    private final HotspotService hotspot;
    private final TripIntentAgent intent;

    // Field-injected so the existing 5-arg constructor used by tests stays valid.
    @Autowired(required = false)
    private AgentEventPublisher events;

    @Autowired(required = false)
    private PolicyEngine policy;

    @Autowired(required = false)
    private DispatchRecommendationRepository recommendations;

    @Autowired(required = false)
    private BookingRepository bookings;

    @Autowired(required = false)
    private BookingService bookingService;

    @Autowired(required = false)
    private NotificationService notifications;

    @Autowired(required = false)
    private CurrentUser currentUser;

    /**
     * Phase 3.3 — global kill switch. When false the autonomous endpoint
     * only persists a PENDING recommendation. Default false — production must
     * opt in explicitly via env var.
     */
    @Value("${tekisi.agent.dispatch.autonomous-enabled:false}")
    private boolean autonomousEnabled;

    public AgentController(DispatchAgent dispatch,
                           EtaService eta,
                           SafetyAgent safety,
                           HotspotService hotspot,
                           TripIntentAgent intent) {
        this.dispatch = dispatch;
        this.eta = eta;
        this.safety = safety;
        this.hotspot = hotspot;
        this.intent = intent;
    }

    public record DispatchRequest(Long bookingId, Double commuterLat, Double commuterLng) {}
    public record EtaRequest(Long bookingId, Long driverId, Double commuterLat, Double commuterLng) {}
    public record SafetyRequest(Long bookingId, List<Ping> pings) {
        public record Ping(double lat, double lng, Instant at) {}
    }
    public record IntentRequest(String text) {}

    @PostMapping("/dispatch")
    public DispatchDecision dispatch(@RequestBody DispatchRequest req) {
        return dispatch.dispatch(req.bookingId(), req.commuterLat(), req.commuterLng(), corr());
    }

    @PostMapping("/dispatch/by-rank")
    public DispatchDecision dispatchByRank(@RequestParam Long bookingId) {
        return dispatch.dispatchByPickupRank(bookingId, corr());
    }

    /**
     * Phase 3.3 — autonomous dispatch endpoint, OPERATOR-only via SecurityConfig.
     *
     * <p>Two gates must be open for the agent to act:
     * <ol>
     *   <li>{@code tekisi.agent.dispatch.autonomous-enabled=true} — the
     *       global kill switch.</li>
     *   <li>{@link PolicyEngine#isDispatchAutonomous(DispatchDecision)} — the
     *       per-decision check (status=ASSIGNED, not degraded, not fallback).</li>
     * </ol>
     * Otherwise the agent persists a PENDING recommendation that operators
     * confirm in the OpsConsole.
     *
     * <p>"Acting" means offering the trip to the recommended driver by
     * notification. The agent never sets a booking to ACCEPTED itself: the
     * fare is the driver's to set, and accepting is what charges the
     * commuter's wallet, so it must go through the normal accept flow.
     *
     * <p>Every call writes a row to {@code agent_schema.dispatch_recommendation}
     * carrying the decision's confidence + reason codes — the canary
     * dashboard reads from this table for shadow-mode quality measurement.
     */
    @PostMapping("/dispatch/auto")
    public Map<String, Object> dispatchAuto(@RequestBody DispatchRequest req) {
        String correlationId = corr();
        DispatchDecision decision = dispatch.dispatch(
                req.bookingId(), req.commuterLat(), req.commuterLng(), correlationId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("decision", decision);

        boolean policyAutonomous = policy != null && policy.isDispatchAutonomous(decision);
        boolean willAct = autonomousEnabled && policyAutonomous;

        DispatchRecommendation rec = new DispatchRecommendation();
        rec.setBookingId(req.bookingId());
        rec.setRecommendedDriverId(decision.assignedDriverId());
        rec.setConfidence(decision.metadata().confidence());
        rec.setReasonCodes(joinReasons(decision));
        rec.setCorrelationId(correlationId);
        rec.setCreatedAt(Instant.now());

        if (!willAct) {
            rec.setStatus(policyAutonomous
                    ? DispatchRecommendation.Status.PENDING
                    : DispatchRecommendation.Status.REJECTED);
            persistRec(rec);
            response.put("autonomous", false);
            response.put("recommendationStatus", rec.getStatus().name());
            response.put("reason", autonomousEnabled
                    ? "policy-blocked"
                    : "autonomous-disabled");
            return response;
        }

        Booking b = bookings != null ? bookings.findById(req.bookingId()).orElse(null) : null;
        if (b == null || b.getStatus() != BookingStatus.PENDING) {
            rec.setStatus(DispatchRecommendation.Status.REJECTED);
            response.put("autonomous", false);
            response.put("reason", "booking-not-pending");
        } else if (decision.assignedDriverId() == null || notifications == null) {
            rec.setStatus(DispatchRecommendation.Status.REJECTED);
            response.put("autonomous", false);
            response.put("reason", "no-driver-assigned");
        } else {
            try {
                notifications.send(decision.assignedDriverId(), Role.DRIVER,
                        "New trip request near you: " + b.getPickupLocation() + " to " + b.getDestination()
                                + ". Open Incoming Requests to set a fare and accept.");
                rec.setStatus(DispatchRecommendation.Status.EXECUTED);
                rec.setDecidedAt(Instant.now());
                response.put("autonomous", true);
                response.put("offeredToDriverId", decision.assignedDriverId());
            } catch (RuntimeException offerFail) {
                rec.setStatus(DispatchRecommendation.Status.REJECTED);
                response.put("autonomous", false);
                response.put("reason", "offer-failed");
            }
        }

        persistRec(rec);
        response.put("recommendationStatus", rec.getStatus().name());
        return response;
    }

    private void persistRec(DispatchRecommendation rec) {
        if (recommendations == null) return;
        try {
            recommendations.save(rec);
        } catch (RuntimeException ignored) {
            // Recommendation persistence is best-effort — never poison the dispatch path.
        }
    }

    private static String joinReasons(DispatchDecision d) {
        if (d.metadata() == null || d.metadata().reasonCodes() == null) return "";
        return d.metadata().reasonCodes().stream()
                .map(Enum::name)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    @PostMapping("/eta/pickup")
    public EtaEstimate etaPickup(@RequestBody EtaRequest req) {
        return eta.estimatePickupEta(req.bookingId(), req.driverId(),
                req.commuterLat(), req.commuterLng(), corr());
    }

    @PostMapping("/safety/evaluate")
    public SafetyAlert evaluate(@RequestBody SafetyRequest req) {
        List<SafetyAgent.GpsPing> pings = req.pings() == null ? List.of() :
                req.pings().stream()
                        .map(p -> new SafetyAgent.GpsPing(p.lat(), p.lng(), p.at()))
                        .toList();
        return safety.evaluate(req.bookingId(), pings, SafetyAgent.ExpectedRoute.ALWAYS_ON_PATH, corr());
    }

    /**
     * Phase 3.4 — commuter / driver panic button.
     *
     * <p>Calls the deterministic SafetyAgent.panic() (already CRITICAL by
     * construction, downgraded to HUMAN_REVIEW_REQUIRED by PolicyEngine) and
     * republishes a {@link SafetyIncidentDetectedEvent} so the
     * {@code OperationalInsightListener} writes a SAFETY_PANIC row AND the
     * new {@code OpsAlertListener} fans out to the operator paging channel.
     */
    @PostMapping("/safety/panic")
    public SafetyAlert panic(@RequestParam Long bookingId) {
        // Open to any authenticated user in SecurityConfig; only the trip's
        // commuter, its driver, or staff may raise the alarm for it.
        if (bookingService != null && currentUser != null) {
            bookingService.requireParticipant(bookingId, currentUser.id(), currentUser.role());
        }
        String correlationId = corr();
        SafetyAlert alert = safety.panic(bookingId, correlationId);
        if (events != null) {
            events.publish(new SafetyIncidentDetectedEvent(alert, Instant.now(), correlationId));
        }
        return alert;
    }

    @GetMapping("/hotspots")
    public HotspotRecommendation hotspots() {
        return hotspot.recommend(corr());
    }

    @PostMapping("/intent/interpret")
    public TripIntentResult interpret(@RequestBody IntentRequest req) {
        return intent.interpret(req.text(), corr());
    }

    /** The request's correlation id (set by CorrelationIdFilter), so decisions line up with logs. */
    private static String corr() {
        String id = CorrelationContext.get();
        return id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
    }
}
