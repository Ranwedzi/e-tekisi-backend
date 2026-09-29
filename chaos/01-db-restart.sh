#!/usr/bin/env bash
# Chaos drill 1: Postgres unavailable for ~30s.
# Pre-condition: docker-compose.prod.yml stack running in staging.
# Post-condition: app recovers without manual intervention; no audit-row loss.
#
# Expected behavior is documented in chaos/EXPECTATIONS.md.

set -euo pipefail

DB_SVC="${DB_SVC:-db}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
PAUSE_SECONDS="${PAUSE_SECONDS:-30}"

echo "[chaos] Snapshot metrics BEFORE"
curl -fsS http://localhost:8080/actuator/prometheus > /tmp/chaos-before.prom

echo "[chaos] Pausing $DB_SVC for ${PAUSE_SECONDS}s"
docker compose -f "$COMPOSE_FILE" pause "$DB_SVC"
sleep "$PAUSE_SECONDS"
docker compose -f "$COMPOSE_FILE" unpause "$DB_SVC"

echo "[chaos] Waiting 60s for app recovery + alert clearing"
sleep 60

echo "[chaos] Snapshot metrics AFTER"
curl -fsS http://localhost:8080/actuator/prometheus > /tmp/chaos-after.prom

echo "[chaos] Liveness check"
curl -fsS http://localhost:8080/actuator/health/liveness | grep -q '"status":"UP"' \
    || { echo "FAIL: liveness not UP after recovery"; exit 1; }

echo "[chaos] PASS — DB freeze drill complete"
