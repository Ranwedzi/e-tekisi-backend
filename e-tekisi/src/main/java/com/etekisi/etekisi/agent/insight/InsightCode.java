package com.etekisi.etekisi.agent.insight;

/**
 * Closed vocabulary of operator-side insight codes.
 *
 * Every operational signal the platform surfaces to operators must reduce
 * to one of these codes — there is no free-text alert path. This is the
 * anti-hallucination boundary for the operator experience.
 */
public enum InsightCode {
    /** Trip completed but few or no GPS pings were recorded. */
    NO_GPS_HISTORY,
    /** Trip fare exceeds the configured high-fare threshold. */
    UNUSUAL_HIGH_FARE,
    /** Trip completed with no fare or zero fare — wallet/payment risk. */
    ZERO_FARE_TRIP,
    /** Driver going prolonged-stationary detected mid-trip. */
    SAFETY_STATIONARY,
    /** Driver deviated from expected route. */
    SAFETY_DEVIATION,
    /** Commuter triggered panic. */
    SAFETY_PANIC,
    /** Generic safety-incident projection from a published SafetyAlert. */
    SAFETY_INCIDENT,
    /** Trip transitioned to ACCEPTED (driver assigned). Phase 2.4. */
    TRIP_STARTED_OK,
    /** Trip completed normally — useful for ops volume metrics. */
    TRIP_COMPLETED_OK,
    /** Trip cancelled / rejected — surfaced as a wastage signal candidate. */
    WASTAGE_CANCELLATION,
    /** A commuter gave a driver 1 or 2 stars, or the driver's average fell low. */
    DRIVER_LOW_RATING,
    /** Payment-risk agent flagged a wallet/payout action for review. Phase 3.6. */
    PAYMENT_RISK_HOLD,
    /** Autonomous dispatch executed by the agent under operator policy. Phase 3.3. */
    DISPATCH_AUTONOMOUS_EXECUTED,
    /** Autonomous dispatch declined — operator confirmation required. Phase 3.3. */
    DISPATCH_RECOMMENDATION_PENDING
}
