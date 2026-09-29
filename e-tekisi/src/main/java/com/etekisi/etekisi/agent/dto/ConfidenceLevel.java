package com.etekisi.etekisi.agent.dto;

/**
 * Coarse confidence band attached to every agent decision.
 * Numeric confidence stays as a double on the decision; the band
 * is what UIs and policy gates branch on so thresholds live in one place.
 */
public enum ConfidenceLevel {
    UNKNOWN,   // data missing/stale — must fall back, must NOT act
    LOW,       // model/rule produced an answer but below action threshold
    MEDIUM,    // actionable with user/driver confirmation
    HIGH;      // actionable autonomously within policy bounds

    public static ConfidenceLevel fromScore(double score) {
        if (Double.isNaN(score) || score < 0.0) return UNKNOWN;
        if (score < 0.50) return LOW;
        if (score < 0.80) return MEDIUM;
        return HIGH;
    }
}
