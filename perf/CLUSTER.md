# Running more than one backend instance

## What has to be shared

| State | One instance | Two or more |
|---|---|---|
| Scheduled jobs (offer expiry, purges, retention, reconciliation, document expiry, demand pulse) | run normally | each run takes a lock in `agent_schema.shedlock` (ShedLock, always on), so it runs on one instance at a time |
| Login throttle counters, MFA sign-in challenges | memory | Redis (`REDIS_ENABLED=true`) |
| Map-service rate limit (1 req/s per OSM service) and cache | memory | Redis: the limit holds for the whole deployment |
| Live-event streams (`/api/events`) | in-process | Redis pub/sub: an offer made through instance B reaches a commuter streaming from instance A |
| Database migrations | Flyway | Flyway's own lock: one instance migrates, the others wait |
| Bootstrap operator account | created at start | first instance creates it; the others see it exists |

Per-instance housekeeping (stream heartbeats, evicting expired in-memory
entries) deliberately runs on every instance.

Without `REDIS_ENABLED=true` the prod profile logs a warning at start-up:
memory state is only correct for one instance.

## Rehearsal: two instances on one machine

`docker-compose.cluster.yml` runs Postgres, Redis, Mailpit, `backend-a`,
`backend-b` and a Caddy load balancer (round robin, health-checked) on
port 8080. The response header `X-Backend` names the instance that answered.

```bash
docker compose stop                                   # the dev stack uses the same ports
docker compose -p tekisi-cluster -f docker-compose.cluster.yml --env-file .env up -d --build
```

Then, with the frontend dev server on :3000 (it talks to :8080):

- the end-to-end suite: `npx playwright test` in the frontend repo, with
  `E2E_OPS_EMAIL` / `E2E_OPS_PASSWORD` from `.env`;
- the load test (`perf/cluster.js`, k6 in Docker on the cluster network):

```bash
docker run --rm --network tekisi-cluster_default -v "$PWD/perf:/perf:ro" \
  -e BASE_URL=http://lb:8080 -e MAILPIT_URL=http://mailpit:8025 \
  -e OPS_EMAIL=... -e OPS_PASSWORD=... grafana/k6:0.54.0 run /perf/cluster.js
```

It signs up 20 commuter/driver pairs (email verification, documents, driver
approval, top-ups), runs whole trips for two minutes (request, offer, accept,
reads, complete) and finishes with a money reconciliation, which must be OK.

Clean up: `docker compose -p tekisi-cluster -f docker-compose.cluster.yml --env-file .env down -v`.

## Results (2026-10-21, one laptop running everything)

| | |
|---|---|
| Trips completed | 2,544 in 2 min (18.3/s), 20 concurrent users, both instances serving |
| Trip request latency | p50 46 ms, p95 173 ms, max 638 ms |
| Failed requests | 0 of 17,868 |
| Reconciliation afterwards | OK: 8,426 ledger entries, no discrepancies |
| Scheduled jobs | `demand-pulse` held by one instance and `offer-expiry` by the other: each run on exactly one |
| End-to-end suite through the load balancer | 13/13 passed (sign-in with MFA, live updates, the money flow, reconciliation) |

The first run (p95 601 ms, 3 failures) found the database pool exhausted:
requests held their connection until the response was written
(open-session-in-view). It is now off (`spring.jpa.open-in-view=false`), and
the cluster stack uses production's INFO logging and 20 connections.

## Going live with two instances

1. Add a Redis (managed, or a `redis:7` container with a password and no
   public port) and set `REDIS_ENABLED=true`, `REDIS_HOST`, `REDIS_PASSWORD`.
2. Run the second backend and list both in Caddy's `reverse_proxy` with
   `lb_policy round_robin` and `health_uri /actuator/health`, as in
   `deploy/cluster/Caddyfile`.
3. Size Postgres connections: instances x `DB_POOL_MAX` (20) must stay under
   Postgres `max_connections` (100 by default) with room for backups and
   monitoring.
