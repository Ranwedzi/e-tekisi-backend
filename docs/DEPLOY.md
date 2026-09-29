# Deploy — Tekisi Backend

Two supported topologies. Pick one per environment; do not mix.

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
- [ ] No new alerts fired in the 10-minute window after switch.
- [ ] One synthetic booking (`tests/smoke.sh`) completes end-to-end
      including audit row + insight row.
- [ ] `tekisi.bookings.created` rate matches pre-deploy baseline within ±20%.

## Anti-patterns explicitly disallowed

- Deploying without an image tag (always pin to SHA).
- Dropping a column and re-deploying the app in the same release.
- Disabling readiness probes to "make the rollout faster."
- Skipping the smoke test because "it's just a config change."
