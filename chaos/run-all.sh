#!/usr/bin/env bash
# Nightly chaos suite — runs all three drills in series with cool-downs.

set -euo pipefail

cd "$(dirname "$0")/.."

mkdir -p chaos/reports
REPORT="chaos/reports/$(date +%Y-%m-%dT%H-%M-%S).log"

run_drill() {
    local script="$1"
    echo "===== $script =====" | tee -a "$REPORT"
    if bash "$script" 2>&1 | tee -a "$REPORT"; then
        echo "[$script] PASS" | tee -a "$REPORT"
    else
        echo "[$script] FAIL" | tee -a "$REPORT"
        return 1
    fi
}

trap 'echo "[chaos] suite aborted"; exit 1' INT TERM

run_drill chaos/01-db-restart.sh
echo "[chaos] cool-down 5m"; sleep 300

run_drill chaos/02-routing-api-500.sh
echo "[chaos] cool-down 5m"; sleep 300

run_drill chaos/03-traffic-spike-10x.sh

echo "[chaos] suite GREEN"
