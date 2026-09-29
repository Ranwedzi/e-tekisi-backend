# Performance Baseline

This document fixes the SLOs Tekisi commits to. Every release that ships a
controller change MUST re-run `perf/baseline.js -e PROFILE=BASELINE` against
staging and update the table below if numbers move by more than 10%.

## SLOs (per endpoint, single-region)

| Endpoint                            | p50    | p95    | p99    | Notes                          |
|-------------------------------------|--------|--------|--------|--------------------------------|
| `POST /api/bookings/create`         | 80 ms  | 250 ms | 500 ms | hot path, advisory dispatch    |
| `PUT /api/bookings/{id}/accept/{driverId}` | 120 ms | 350 ms | 700 ms | wallet debit + notifications   |
| `PUT /api/bookings/{id}/complete/{driverId}` | 100 ms | 300 ms | 600 ms | wallet credit + insight emit   |
| `GET /api/bookings/pending`         | 60 ms  | 200 ms | 400 ms | indexed query                  |
| `POST /api/locations/driver`        | 40 ms  | 150 ms | 300 ms | + async safety re-eval         |

p95 targets must hold at the **PEAK** profile (3× baseline traffic).
At **SPIKE** (10× baseline) the agreed degradation envelope is p95 ≤ 1.5 s.

## Profiles

| Profile  | Concurrent VUs | Duration | Purpose                                          |
|----------|----------------|----------|--------------------------------------------------|
| BASELINE | 5              | 2 min    | Fast pre-merge sanity, fits inside CI < 3 min.   |
| PEAK     | 20             | 5 min    | Pre-release gate; matches expected production.   |
| SPIKE    | 100            | 5 min    | Chaos drill 3; verifies bounded pool degrades.   |

## How to run

```bash
# 1. Seed staging with one commuter and one driver, capture IDs:
export COMMUTER_ID=1 DRIVER_ID=1 BOOKING_ID=1
export COMMUTER_EMAIL=commuter@example.com COMMUTER_PASSWORD=...
export DRIVER_EMAIL=driver@example.com DRIVER_PASSWORD=...

# 2. Run the desired profile:
k6 run -e BASE_URL=https://staging.tekisi -e PROFILE=PEAK perf/baseline.js

# 3. k6 prints the pass/fail summary against the threshold; copy any
#    p95 movement > 10% into the table above and PR the change.
```

## First-bottleneck record

Record here the first thing that gives at the next-higher profile. Update
when fixed.

| Date       | Profile | First-to-give | Symptom                                      | Fix shipped               |
|------------|---------|---------------|----------------------------------------------|---------------------------|
| (initial)  | SPIKE   | agentTaskExecutor queue | `tekisi.events.dropped` rises   | Phase 2.4 bounded pool — accepted |
|            |         |                |                                              |                           |

## What we explicitly do NOT measure here

- LLM-intent latency (Phase 3.5) — separate budget.
- Routing-API tail latency — owned by the upstream provider, gated by the
  Resilience4j time-limiter at 800ms.
- Frontend rendering — different ownership.

## N+1 query detector

The CI test job sets `org.hibernate.SQL_SLOW=DEBUG` for the agent test slice
and fails the build if any test logs > 25 SQL statements per HTTP request.
This catches accidental N+1 patterns at PR time, before they surface in this
baseline.
