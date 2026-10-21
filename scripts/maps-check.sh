#!/usr/bin/env sh
# Checks the self-hosted map services (docker-compose.maps.yml) from inside
# the stack's network, the way the backend and Caddy reach them.
#
#   sh scripts/maps-check.sh                 # uses .env.prod
#   ENV_FILE=.env.staging sh scripts/maps-check.sh
#
# Bree Taxi Rank to Soweto, Johannesburg. Exits non-zero if any service fails.
set -u

ENV_FILE=${ENV_FILE:-.env.prod}
compose() {
  docker compose -f docker-compose.prod.yml -f docker-compose.maps.yml --env-file "$ENV_FILE" "$@"
}

fail=0
check() {
  name=$1
  url=$2
  expect=$3
  out=$(compose exec -T caddy wget -q -T 15 -O - "$url" 2>/dev/null | head -c 2000)
  if printf '%s' "$out" | grep -q "$expect"; then
    echo "ok    $name"
  else
    echo "FAIL  $name ($url)"
    fail=1
  fi
}

check "osrm (route)"         "http://osrm:5000/route/v1/driving/28.0368,-26.2005;27.8540,-26.2485?overview=false" '"code":"Ok"'
check "nominatim (search)"   "http://nominatim:8080/search?format=json&limit=1&q=Johannesburg" '"lat"'
check "overpass (query)"     "http://overpass/api/interpreter?data=%5Bout%3Ajson%5D%3Bnode(-26.21,28.03,-26.19,28.05)%3Bout%201%3B" '"elements"'
check "tiles (zoom 0)"       "http://tiles/tile/0/0/0.png" 'PNG'

exit $fail
