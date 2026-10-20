# Deploy — Tekisi

## Start here — single server with Caddy

`docker-compose.prod.yml` (repo root) is the supported way to run one
production server: Caddy terminates HTTPS (automatic Let's Encrypt) and routes
`/api/*` and `/actuator/health` to the backend and everything else to the
frontend. Backend, frontend and Postgres are not published on the host.

1. Point `DOMAIN`'s DNS at the server; open ports 80 and 443.
2. `cp .env.prod.example .env.prod`, fill in every value, `chmod 600 .env.prod`.
   Store the secrets (especially `DATA_ENCRYPTION_KEY`) in your password
   manager or vault as well.
3. `docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build`
4. Check `https://$DOMAIN/actuator/health` returns `UP`, then sign in at
   `https://$DOMAIN/login` with the bootstrap operator ("e-Tekisi staff?").
5. **Rehearse a safety alert before any real user rides.** With
   `SAFETY_ALERT_WEBHOOK` set, sign up a test commuter, request a trip and
   press the SOS button. Check that the on-call phone/channel gets the page
   within a minute, then open `/ops` → Safety alerts and press "I'm handling
   this". The backend logs `agent.ops.alert.webhook_unset` at start-up if
   the webhook is missing, and `agent.ops.alert.webhook_failed` for each
   failed attempt (three per alert). If Prometheus is running, an alert left
   unacknowledged for 5 minutes fires `SafetyAlertUnacknowledged` as a
   second page.

**Updating:** `git pull` in both repos, then re-run step 3. Flyway migrates
the database on start; the backend refuses to start if the entities and
schema disagree. Take a backup first (`docs/BACKUP_RESTORE.md`).

**Keep these volumes:** `tekisi_pgdata` (the database) and `caddy_data`
(certificates; Let's Encrypt rate-limits re-issuing).

**Not routed publicly:** Swagger UI, OpenAPI docs, `/actuator/prometheus`.
Prometheus reaches the metrics on the internal network.

### Monitoring

The stack includes Prometheus, Alertmanager and Grafana, listening on
`127.0.0.1` only. From your machine:

```bash
ssh -L 3001:localhost:3001 -L 9090:localhost:9090 -L 9093:localhost:9093 you@server
```

- **Grafana** http://localhost:3001 (user `admin`, `GRAFANA_ADMIN_PASSWORD`):
  the "e-Tekisi overview" dashboard (traffic, errors, p95 latency, bookings,
  safety alerts, JVM, DB pool).
- **Prometheus** http://localhost:9090/alerts: rules from
  `monitoring/alerts.yml`, including `BackendDown`, `HttpServerErrorRateHigh`
  and `SafetyAlertUnacknowledged`.
- **Alertmanager** http://localhost:9093 sends firing alerts to
  `MONITORING_ALERT_WEBHOOK` / `MONITORING_SLACK_WEBHOOK` /
  `MONITORING_PAGERDUTY_KEY`. Set at least one.

All of this runs on the same server, so it can't tell you the server itself
is gone. Add an **external uptime check** (UptimeRobot, Better Stack,
Grafana Cloud synthetic checks, ...) on `https://$DOMAIN/actuator/health`
that pages the same on-call channel.

### Backups

The `backup` service dumps the database daily; see
`docs/BACKUP_RESTORE.md` for encryption, the off-site copy and the monthly
restore drill (`run --rm backup restore-drill`).

The two topologies below are for when one server is no longer enough.

---

Two scaled-out topologies. Pick one per environment; do not mix.

## Topology A — Two Compose stacks behind a manual switch (single VM)

For staging / single-VM production.

```
              ┌─────────────────────┐
              │   Reverse proxy     │   nginx / Caddy / Traefik
              │   (one config file) │
              └─────────┬───────────┘
                        │
             ┌──────────┴──────────┐
             │                     │
       ┌─────▼─────┐         ┌─────▼─────┐
       │ tekisi-blue│         │tekisi-green│   one is "live", one is "dark"
       │  :8081     │         │  :8082     │
       └─────┬─────┘         └─────┬─────┘
             │                     │
             └──────────┬──────────┘
                        │
                 ┌──────▼──────┐
                 │  Postgres   │   shared
                 └─────────────┘
```

### Procedure

1. **Bring up the dark stack on the new image.**
   ```bash
   IMAGE_TAG=$NEW_SHA \
   docker compose -f docker-compose.prod.yml -p tekisi-green up -d --build
   ```

2. **Wait for readiness.**
   ```bash
   until curl -fsS http://localhost:8082/actuator/health/readiness \
       | grep -q '"status":"UP"'; do sleep 2; done
   ```

3. **Smoke test against the dark stack.**
   ```bash
   tests/smoke.sh http://localhost:8082
   ```

4. **Switch the proxy.**
   - Replace `proxy_pass http://tekisi-blue:8080` with
     `proxy_pass http://tekisi-green:8080` in the nginx config.
   - `nginx -s reload` — zero-dropped-connection switch.

5. **Watch dashboards for 10 minutes.** Roll back by reversing step 4.

6. **Tear down the old stack.**
   ```bash
   docker compose -p tekisi-blue down
   ```

### Rollback

Steps 4 and 5 are reversible without DB rollback because **all migrations
are backward-compatible**. The contract is: any V<n+1> migration only adds
nullable columns / new tables / new indexes. Destructive changes
(drop column, narrow type, NOT NULL on existing column) require a
two-release dance: V<n+1> adds, V<n+2> migrates and removes.

## Topology B — Kubernetes rolling update

For multi-replica production.

`k8s/deployment.yaml` (excerpt):

```yaml
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  template:
    spec:
      containers:
        - name: tekisi
          image: ghcr.io/etekisi/tekisi-backend:${SHA}
          ports: [{ containerPort: 8080 }]
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 20
            periodSeconds: 5
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 60
            periodSeconds: 30
          envFrom:
            - secretRef: { name: tekisi-secrets }
```

The `maxUnavailable: 0` + `maxSurge: 1` combo gives you a one-pod canary
without any user-visible capacity reduction.

### Procedure

```bash
kubectl set image deployment/tekisi tekisi=ghcr.io/etekisi/tekisi-backend:$NEW_SHA
kubectl rollout status deployment/tekisi --timeout=5m
```

If the readiness probe fails on the new pod, the rollout halts automatically
and serves traffic exclusively from the old pods.

### Rollback

```bash
kubectl rollout undo deployment/tekisi
kubectl rollout status deployment/tekisi
```

## Pre-deploy checklist

- [ ] CI green on the merge commit (`mvn verify` + secret-grep + dependency-check).
- [ ] Image tag matches the commit SHA (no `latest`).
- [ ] Flyway migrations checked in are backward-compatible with the
      currently-deployed app.
- [ ] `application-prod.properties` did not gain any new required env var
      that is not already set in the secret manager.
- [ ] On-call notified in #ops-deploys with the SHA + change summary.

## Post-deploy verification

- [ ] `/actuator/health` returns `UP` on every replica.
- [ ] Prometheus → Status → Targets shows `tekisi-backend` UP.
- [ ] `docker compose ps` shows `backup` healthy (after the first run).
- [ ] No new alerts fired in the 10-minute window after switch.
- [ ] One synthetic booking (`tests/smoke.sh`) completes end-to-end
      including audit row + insight row.
- [ ] `tekisi.bookings.created` rate matches pre-deploy baseline within ±20%.

## Anti-patterns explicitly disallowed

- Deploying without an image tag (always pin to SHA).
- Dropping a column and re-deploying the app in the same release.
- Disabling readiness probes to "make the rollout faster."
- Skipping the smoke test because "it's just a config change."
