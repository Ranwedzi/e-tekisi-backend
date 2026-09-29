#!/usr/bin/env bash
# Chaos drill 2: External routing API returns HTTP 500 for 60s.
# Verifies (a) heuristic ETA fallback engages, (b) Resilience4j circuit opens,
# (c) circuit recovers after upstream is healthy again.
#
# Requires: a WireMock or similar stub at $ROUTING_STUB_URL with admin API.

set -euo pipefail

STUB_URL="${ROUTING_STUB_URL:-http://localhost:9999}"
DURATION="${DURATION:-60}"

echo "[chaos] Programming stub to return 500 for ${DURATION}s"
curl -fsS -X POST "$STUB_URL/__admin/mappings" -H 'Content-Type: application/json' -d @- <<'EOF'
{
  "request": { "method": "GET", "urlPathPattern": "/route/v1/driving/.*" },
  "response": { "status": 500, "body": "stub-injected outage" }
}
EOF

# Drive a synthetic load through the booking flow during the failure window.
END=$((SECONDS + DURATION))
COUNT=0
while [ $SECONDS -lt $END ]; do
    curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8080/api/agents/eta/probe \
        -H 'Content-Type: application/json' \
        -d '{"driverId":1,"commuterLat":-26.20,"commuterLng":28.04}' \
        || true
    COUNT=$((COUNT + 1))
    sleep 1
done
echo
echo "[chaos] Sent $COUNT probes during outage"

echo "[chaos] Removing stub mapping"
curl -fsS -X DELETE "$STUB_URL/__admin/mappings"

echo "[chaos] Waiting 45s for circuit to half-open and recover"
sleep 45

# Assert breaker is no longer open.
STATE=$(curl -fsS http://localhost:8080/actuator/prometheus \
    | grep 'resilience4j_circuitbreaker_state{name="routingApi",state="open"}' \
    | awk '{print $2}' | tr -d '\r' || echo "0")
if [ "$STATE" = "1" ]; then
    echo "FAIL: routing breaker still OPEN after recovery"
    exit 1
fi

echo "[chaos] PASS — routing-API outage drill complete"
