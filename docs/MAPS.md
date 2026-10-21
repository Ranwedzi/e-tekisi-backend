# Self-hosted maps

e-Tekisi uses four OpenStreetMap services:

| Service | Used for | Public default | Self-hosted (`docker-compose.maps.yml`) |
|---|---|---|---|
| Nominatim | turning "Bree Taxi Rank" into coordinates (booking) | nominatim.openstreetmap.org | `nominatim`, `http://nominatim:8080` |
| OSRM | trip routes on the map, distances | router.project-osrm.org | `osrm`, `http://osrm:5000` |
| Overpass | finding taxi ranks near the commuter | overpass-api.de | `overpass`, `http://overpass/api/interpreter` |
| Tiles | the street map itself | tile.openstreetmap.org | `tiles`, served by Caddy at `/tiles/{z}/{x}/{y}.png` |

The public servers are run by volunteers for light use. Their usage policies
forbid an app's production traffic, so the backend limits itself to one
request a second to each (with caching), which only works for a pilot. **Run
your own before real traffic**, or contract a provider for the same APIs.

When a URL points anywhere other than the public servers, the backend stops
throttling that service (`OsmGateway`); it logs which services are throttled
at start-up (`osm.services ... rateLimited=[...]`).

## Running them

Everything is in `docker-compose.maps.yml`, on the production stack's
network. Plan for:

- **Disk:** about 100 GB for the South Africa extract. Tiles take the most,
  and their cache grows with use.
- **RAM:** 8 GB minimum, 16 GB comfortable.
- **CPU:** 4+ cores, set as `MAPS_THREADS`.

It can share the app server if it's that size, or run on a second machine.
For a second machine, publish the ports there behind a firewall and use its
address in the URLs below.

1. In `.env.prod`, set:

   ```
   NOMINATIM_PASSWORD=<long random string>
   MAPS_THREADS=4
   OSM_NOMINATIM_URL=http://nominatim:8080
   OSM_OSRM_URL=http://osrm:5000
   OSM_OVERPASS_URL=http://overpass/api/interpreter
   MAP_TILE_URL=/tiles/{z}/{x}/{y}.png
   ```

2. Start the maps first and let them import. The first start downloads the
   extract (~400 MB, South Africa with Lesotho and Eswatini) and imports it
   into each service:

   ```
   docker compose -f docker-compose.prod.yml -f docker-compose.maps.yml --env-file .env.prod up -d maps-download osrm nominatim overpass tiles
   ```

   Rough timings on 4 cores:
   - OSRM: 15–30 minutes;
   - Overpass: about 30 minutes;
   - Nominatim: 1–2 hours;
   - tiles: 1–3 hours, which includes downloading the coastline and water data.

   Follow the imports with `docker compose ... logs -f nominatim tiles`.

3. When `sh scripts/maps-check.sh` passes, rebuild and restart the app with
   the new settings. `MAP_TILE_URL` is built into the frontend, so the
   frontend needs `--build`:

   ```
   docker compose -f docker-compose.prod.yml -f docker-compose.maps.yml --env-file .env.prod up -d --build
   ```

4. Check the backend log line `osm.services ... rateLimited=[]`, then book a
   trip on a phone: the map, the pick-up search and the route should all work.

Always pass both `-f` files from now on, or the map services stop with the next `up`.

Tested: OSRM, Nominatim and Overpass were brought up from this compose file
on a small extract (`MAPS_PBF_URL`/`MAPS_UPDATES_URL` pointed at Saint
Helena) and answered routing, search and rank queries. The tile server
wasn't: its import downloads about 1 GB of coastline data. Try it on staging
first.

## Keeping the data fresh

- **Nominatim** and **Overpass** apply Geofabrik's daily changes by
  themselves.
- **OSRM** and **tiles** keep what they imported. Re-import every month or
  two, when new roads or ranks matter. Remove the downloaded extract and
  that service's volume, then start it again:

  ```
  docker compose -f docker-compose.prod.yml -f docker-compose.maps.yml --env-file .env.prod rm -sf osrm osrm-prepare tiles maps-download
  docker volume rm <project>_maps_pbf <project>_osrm_data          # routing
  docker volume rm <project>_osm_tiles_db <project>_osm_tiles      # tiles
  docker compose -f docker-compose.prod.yml -f docker-compose.maps.yml --env-file .env.prod up -d
  ```

  During the re-import, routing falls back to straight-line estimates and
  the map shows grey tiles. Do it at a quiet time.

## When something goes wrong

- **The backend returns 502 or 503 for map calls:** run `sh scripts/maps-check.sh`.
  The service that fails it is the one to look at (`docker compose ... logs <service>`).
- **An import stopped halfway** (out of disk or memory): remove that
  service's volume and start it again. The tile server in particular won't
  retry an import over a half-built database.
- **Overpass logs "Error while downloading diffs … status code 3"** right
  after an import: there's nothing newer than the extract yet. It clears
  once Geofabrik publishes the next daily change.
- **Overpass answers "Permission denied … osm3s_osm_base":** the `/db`
  volume lost its open mode; the compose file's entrypoint fixes it on start,
  so restart the service.
- **Grey map:** `MAP_TILE_URL` is baked in at build time. Rebuild the
  frontend after changing it, and check `https://<domain>/tiles/0/0/0.png`.
- **Attribution:** keep "© OpenStreetMap contributors" on the map, which is
  the frontend's default. The ODbL licence requires it.
