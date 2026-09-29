package com.etekisi.etekisi.agent.dto;

/**
 * Closed vocabulary of machine-readable reason codes.
 * Free-text reasons are forbidden — every "why" must reduce to one of these
 * so agents cannot smuggle hallucinated explanations into operational state.
 */
public enum ReasonCode {
    // generic
    OK,
    NO_DATA,
    STALE_DATA,
    INSUFFICIENT_CONFIDENCE,
    POLICY_BLOCKED,
    SCHEMA_INVALID,
    FALLBACK_USED,
    DEGRADED_INPUTS,

    // dispatch
    DRIVER_OUT_OF_RANGE,
    DRIVER_RECENTLY_DECLINED,
    DRIVER_NOT_ACTIVE,
    DRIVER_LOCATION_STALE,
    QUEUE_POSITION_FIRST,
    QUEUE_POSITION_OK,
    NO_CANDIDATE_DRIVERS,
    SCORED,

    // eta
    ETA_HEURISTIC,
    ETA_MODEL,
    ETA_ROUTING_API,
    ETA_ROUTING_FAILED,
    ETA_OUT_OF_RANGE,

    // safety
    ROUTE_ON_TRACK,
    ROUTE_DEVIATION,
    PROLONGED_STATIONARY,
    PANIC_TRIGGERED,
    INSUFFICIENT_GPS_HISTORY,

    // hotspot
    DEMAND_HISTORICAL,
    DEMAND_LIVE_PENDING,
    DEMAND_INSUFFICIENT_HISTORY,

    // intent
    INTENT_RESOLVED,
    INTENT_AMBIGUOUS,
    INTENT_RANK_UNRESOLVED,
    INTENT_ROUTE_UNRESOLVED,
    INTENT_LLM_DISABLED,
    INTENT_LLM_FAILURE,

    // payment-risk (Phase 3.6)
    PAYMENT_AMOUNT_NORMAL,
    PAYMENT_AMOUNT_HIGH,
    PAYMENT_AMOUNT_VERY_HIGH,
    PAYMENT_NEW_USER,
    PAYMENT_VELOCITY_HIGH,
    PAYMENT_INSUFFICIENT_FUNDS,
    PAYMENT_METHOD_UNVERIFIED,

    // dispatch (Phase 3.3 autonomous)
    DISPATCH_AUTONOMOUS_REJECTED,
    DISPATCH_AUTONOMOUS_RECOMMENDED,
    DISPATCH_AUTONOMOUS_EXECUTED
}
