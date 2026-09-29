package com.etekisi.etekisi.agent.listener;

import com.etekisi.etekisi.agent.guardrail.AgentProperties;
import com.etekisi.etekisi.agent.guardrail.Geo;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-driver hot-path throttle for safety re-evaluation.
 *
 * Semantics:
 *   - applied ONLY to expensive, idempotent re-evaluations (e.g. SafetyAgent).
 *   - never applied to GPS persistence — pings are always saved.
 *   - never applied to panic-class events — those bypass this throttle by
 *     contract (panic flows do not run through this gate).
 *
 * Anti-hallucination posture:
 *   - throttle decisions are pure functions of bounded inputs (driverId,
 *     elapsed time, haversine distance) — no inference, no guessing
 *   - missing config (interval=0 or movement=0) disables that dimension only,
 *     never silently "fails open" beyond what the operator configured
 *   - if shouldEvaluate returns false, the caller must NOT fabricate a
 *     decision — it simply skips re-evaluation; the next event will retry
 */
@Component
public class DriverLocationThrottler {

    private final AgentProperties props;
    private final ConcurrentMap<Long, State> lastByDriver = new ConcurrentHashMap<>();

    public DriverLocationThrottler(AgentProperties props) {
        this.props = props;
    }

    /**
     * Returns true if the listener should run a fresh evaluation for this
     * driver right now, false if the event should be suppressed because
     * neither enough time has passed nor enough movement has occurred since
     * the last evaluation.
     *
     * Always returns true on the first event for a previously-unseen driver.
     */
    public boolean shouldEvaluate(Long driverId, double lat, double lng, Instant now) {
        if (driverId == null || now == null) return true;

        long minIntervalSec = Math.max(0L, props.getThrottle().getLocationMinIntervalSeconds());
        double minMoveMeters = Math.max(0.0, props.getThrottle().getLocationMinMovementMeters());
        if (minIntervalSec == 0L && minMoveMeters == 0.0) {
            // throttling disabled entirely — never block evaluations
            recordEvaluated(driverId, lat, lng, now);
            return true;
        }

        State previous = lastByDriver.get(driverId);
        if (previous == null) {
            recordEvaluated(driverId, lat, lng, now);
            return true;
        }

        Duration sinceLast = Duration.between(previous.at, now);
        boolean intervalElapsed = sinceLast.getSeconds() >= minIntervalSec;
        double movedMeters = Geo.haversineMeters(previous.lat, previous.lng, lat, lng);
        boolean movedEnough = movedMeters >= minMoveMeters;

        // EITHER condition lets us evaluate. We only suppress when BOTH are
        // unmet — otherwise we would silently drop signals from a moving
        // driver during a quiet period (a real safety risk).
        if (intervalElapsed || movedEnough) {
            recordEvaluated(driverId, lat, lng, now);
            return true;
        }
        return false;
    }

    private void recordEvaluated(Long driverId, double lat, double lng, Instant at) {
        lastByDriver.put(driverId, new State(lat, lng, at));
    }

    /** Test/diagnostic hook. */
    public void clear() { lastByDriver.clear(); }

    private record State(double lat, double lng, Instant at) {}
}
