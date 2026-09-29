# Chaos Expectations

Each chaos script in this folder injects a controlled failure into a staging
deployment of Tekisi. For each script, this document records:

- **Trigger**: what the script does.
- **Expected user-facing behavior**: what a real commuter / driver sees.
- **Expected internal behavior**: which fallbacks fire, which metrics move,
  which alerts must trigger.
- **Pass criteria**: the binary check the on-call can apply to declare the
  drill successful.

Every chaos run produces a report (timestamped JSON in `chaos/reports/`) that
diffs observed metrics + alerts against this document. A run is GREEN when all
three scripts pass; RED if any single criterion fails.

---

## 1) `01-db-restart.sh` — Postgres unavailable for ~30s

**Trigger:** `docker compose -f docker-compose.prod.yml restart db` followed by
a 30-second freeze (`pause/unpause`).

**Expected user-facing behavior:**
- HTTP 5xx surfaces transiently on writes (booking create, accept, complete)
  during the freeze.
- Reads with cached responses (idempotency replay) succeed.
- Once Postgres recovers, all subsequent requests succeed within ~5 s without
  manual intervention.

**Expected internal behavior:**
- `HikariPoolNearExhaustion` may fire briefly — acceptable.
- `HttpServerErrorRateHigh` fires within 5 minutes; clears within 10 minutes
  of recovery.
- `LivenessProbeFailing` does NOT fire (the JVM stays up; readiness, not
  liveness, drops).
- No `EventDropped` (events for the duration are queued and drained).
- No data corruption: idempotency table prevents duplicate bookings on retry.

**Pass criteria:**
- All in-flight bookings either succeed once or return a uniform `503` error
  envelope (RFC 7807) — never a partial state.
- `tekisi.audit.persist_failures` total stays at 0 once Postgres is back
  (failures during the freeze are logged but caught by the listener wrapper).

---

## 2) `02-routing-api-500.sh` — External routing API returns HTTP 500 for 60s

**Trigger:** WireMock or a matching stub configured to return `500` on the
routing endpoint for 60 seconds, then resume normal responses.

**Expected user-facing behavior:**
- ETAs continue to be returned (heuristic provider in the chain still
  responds).
- ETA confidence drops in the audit, but no request fails.

**Expected internal behavior:**
- After ~10 failed calls within the sliding window the
  `routingApi` Resilience4j circuit breaker transitions to `OPEN`.
- `RoutingApiCircuitOpen` alert fires within 1–2 minutes.
- `tekisi.eta.fallback_used` and `tekisi.routing.timeouts` counters increment
  steadily during the failure window.
- Once 30 s wait elapses post-recovery, breaker transitions to `HALF_OPEN`,
  successful calls promote it back to `CLOSED`.

**Pass criteria:**
- No request returns `5xx` to the commuter.
- `RoutingApiCircuitOpen` fires AND clears.
- ETA decisions in the audit show a transition from `ETA_ROUTING_API`
  reason → `ETA_HEURISTIC` reason → back to `ETA_ROUTING_API`.

---

## 3) `03-traffic-spike-10x.sh` — 10× normal request rate for 5 minutes

**Trigger:** `k6 run perf/spike.js` with `RATE=10x` and `DURATION=5m`.

**Expected user-facing behavior:**
- p95 stays under 1.5 s (vs. 800 ms SLO baseline) — degraded but acceptable.
- A small fraction of requests (< 1%) may hit `503` from queue overflow.

**Expected internal behavior:**
- `agentTaskExecutor` queue fills, some events dropped via the
  `RejectedExecutionHandler` — `tekisi.events.dropped{listener=agentTaskExecutor}`
  increments.
- `EventDropped` alert fires (warning) — expected but must NOT fire above
  ~50 events/min.
- `BookingCreateLatencyHigh` alert may fire — acceptable for the spike
  window.
- HikariCP active connections approach but do not exceed `maximum-pool-size`.

**Pass criteria:**
- Process does not OOM or restart.
- Liveness probe never fails (`LivenessProbeFailing` stays silent).
- After traffic returns to baseline, all alerts clear within 10 minutes.
- Audit table receives a row for every successful booking — no decision-audit
  gaps.

---

## How to run the full nightly suite

```bash
./chaos/run-all.sh staging | tee chaos/reports/$(date +%Y-%m-%d).log
```

`run-all.sh` invokes the three scripts in series with a 5-minute cool-down
between each, snapshots Prometheus + AlertManager state, and writes a JSON
report. CI runs this nightly and pages on RED.
