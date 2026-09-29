/**
 * Tekisi AI-agent layer.
 *
 * Layout:
 *   dto         — bounded result schemas (records) shared by every component
 *   guardrail   — TrustedDataService, DecisionGate, PolicyEngine, SchemaGuard
 *   audit       — DecisionAuditLogger + JPA-persisted decision_audit table
 *   dispatch    — deterministic dispatch & matching decision engine
 *   eta         — heuristic ETA service (model-ready)
 *   safety      — rules-based safety / route-deviation engine
 *   hotspot     — rules + history-driven demand hotspot recommendations
 *   intent      — Trip Intent agent (LLM-ready, validation-first)
 *   controller  — single REST surface for the agent layer
 *
 * Anti-hallucination rules every component obeys:
 *   1. Retrieval before reasoning (TrustedDataService is the only source)
 *   2. Schema-bounded outputs (records in agent.dto)
 *   3. Confidence + reason codes on every decision
 *   4. Deterministic fallback when confidence is low or data is stale
 *   5. Every decision is written to decision_audit
 */
package com.etekisi.etekisi.agent;
