#!/usr/bin/env bash
# Runs the frontend's Playwright suite against the full stack
# (docker-compose.yml: Postgres, backend, Mailpit, frontend) on a fresh,
# throwaway database. Used by CI in both repos; works on a laptop too.
#
#   scripts/ci-e2e.sh <path-to-frontend-repo>
#
# Needs Docker (Compose 2.24+), Node 20+ and free ports 3000, 5433, 8025 and
# 8080, so stop your dev stack first. Credentials are generated per run and
# never written to .env; your ./postgres_data is not touched. Everything is
# removed at the end unless KEEP_STACK=1.
#
# Locally (stop the dev stack first; it is left stopped):
#   docker compose stop && SKIP_NPM_CI=1 scripts/ci-e2e.sh ../path/to/frontend; docker compose start
set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FRONTEND_DIR="$(cd "${1:?usage: ci-e2e.sh <frontend dir>}" && pwd)"
# Docker on Windows (Git Bash) needs C:/... rather than /c/...
DOCKER_FRONTEND_DIR="$(cd "$FRONTEND_DIR" && (pwd -W 2>/dev/null || pwd))"
PROJECT="tekisi-e2e"
cd "$BACKEND_DIR"

if docker ps --format '{{.Names}}' | grep -qE '^etekisi_(db|backend|frontend|mailpit)$'; then
  echo "The dev stack is running (docker compose ps). Stop it first: docker compose stop" >&2
  exit 1
fi

ENV_FILE="$(mktemp)"
OPS_EMAIL="ops-e2e@example.test"
OPS_PASSWORD="$(openssl rand -hex 16)"
cat > "$ENV_FILE" <<ENV
POSTGRES_USER=etekisi
POSTGRES_PASSWORD=$(openssl rand -hex 16)
POSTGRES_DB=etekisi_db
ADMIN_BOOTSTRAP_EMAIL=${OPS_EMAIL}
ADMIN_BOOTSTRAP_PASSWORD=${OPS_PASSWORD}
FRONTEND_DIR=${DOCKER_FRONTEND_DIR}
ENV
compose() {
  docker compose -p "$PROJECT" --project-directory "$BACKEND_DIR" --env-file "$ENV_FILE"     -f "$BACKEND_DIR/docker-compose.yml" -f "$BACKEND_DIR/docker-compose.e2e.yml" "$@"
}

cleanup() {
  status=$?
  if [ $status -ne 0 ]; then
    echo "::group::docker compose logs"
    compose logs --no-color --tail=300 || true
    echo "::endgroup::"
  fi
  if [ "${KEEP_STACK:-0}" != "1" ]; then
    compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  rm -f "$ENV_FILE"
  exit $status
}
trap cleanup EXIT

compose up -d --build

wait_for() {
  local url="$1" name="$2"
  for _ in $(seq 1 120); do
    if curl -fsS -o /dev/null "$url"; then echo "$name is up"; return 0; fi
    sleep 3
  done
  echo "$name did not come up at $url" >&2
  return 1
}
wait_for http://localhost:8080/actuator/health backend
wait_for http://localhost:3000/login frontend
wait_for http://localhost:8025/api/v1/messages mailpit

cd "$FRONTEND_DIR"
# SKIP_NPM_CI=1 reuses an existing node_modules (e.g. while a dev server holds it open on Windows).
if [ "${SKIP_NPM_CI:-0}" != "1" ]; then npm ci; fi
npx playwright install --with-deps chromium
E2E_OPS_EMAIL="$OPS_EMAIL" E2E_OPS_PASSWORD="$OPS_PASSWORD" CI=true npx playwright test
