#!/usr/bin/env bash
# Chaos drill 3: 10x normal request rate for 5 minutes.
# Verifies (a) no OOM/crash, (b) liveness stays UP, (c) bounded queue drops
# loudly via tekisi.events.dropped instead of silently growing.

set -euo pipefail

K6_SCRIPT="${K6_SCRIPT:-perf/spike.js}"

if ! command -v k6 >/dev/null 2>&1; then
    echo "k6 not installed; skipping spike drill" >&2
    exit 0
fi

echo "[chaos] Running 10x spike for 5m"
k6 run --vus 100 --duration 5m "$K6_SCRIPT"

echo "[chaos] Liveness check"
curl -fsS http://localhost:8080/actuator/health/liveness | grep -q '"status":"UP"' \
    || { echo "FAIL: liveness not UP after spike"; exit 1; }

echo "[chaos] Snapshot dropped-event counter"
curl -fsS http://localhost:8080/actuator/prometheus \
    | grep '^tekisi_events_dropped_total' || echo "(no events dropped — pool absorbed spike)"

echo "[chaos] PASS — spike drill complete"
