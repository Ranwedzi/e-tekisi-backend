# e-Tekisi

[![Backend CI](https://github.com/Ranwedzi/e-tekisi-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/Ranwedzi/e-tekisi-backend/actions/workflows/ci.yml)
[![Frontend CI](https://github.com/Ranwedzi/e-tekisi-frontend/actions/workflows/ci.yml/badge.svg)](https://github.com/Ranwedzi/e-tekisi-frontend/actions/workflows/ci.yml)

A minibus-taxi app for South Africa. A commuter requests a ride and drivers
nearby offer a fare. The commuter accepts one and pays from an in-app wallet;
nothing is charged before that. Both follow the trip on a map until the
driver completes it and both rate each other. Operators run a console to vet
drivers, approve payouts, check the money and respond to safety alerts.

This repository holds the **backend** (Java 21, Spring Boot 4, PostgreSQL),
the Docker setups that run the whole system, and the project docs. The web
app is in [e-tekisi-frontend](https://github.com/Ranwedzi/e-tekisi-frontend).

> **Status:** ready for a closed, invite-only pilot on test money. Before a
> public launch it still needs a real payment provider (Paystack), legal and
> regulatory sign-off, and some operations work. See
> [Project status](#project-status).

## What it does

**Commuters**
- Request a taxi now or book one up to 14 days ahead.
- Get fare offers from drivers and accept one; the fare is only charged
  then, from the wallet.
- See the taxi on the map, chat with or call the driver, share the trip with
  family, and press the emergency button.
- Get notifications even with the app closed, and use the app in English,
  isiZulu, Sesotho, Afrikaans or Xitsonga.

**Drivers**
- Sign up, upload licence, PrDP and operating licence, and wait for approval.
- Go online, see nearby requests with distance and the commuter's rating,
  and offer a fare.
- Mark "I've arrived" and "trip started", get directions, complete the trip,
  join rank queues, and request payouts.

**Operators** (the `/ops` console)
- Approve drivers and review their documents.
- Approve payouts and look into payouts held as risky.
- Acknowledge safety alerts; on-call staff are paged automatically.
- Run the daily money reconciliation and see its results.

## How it fits together

```
 phone / browser
      │  https
      ▼
 ┌──────────┐   /api/*   ┌─────────────────────────┐   ┌────────────┐
 │  Caddy   │──────────▶│ backend (Spring Boot)   │──▶│ PostgreSQL │
 │  (HTTPS) │            │ REST API · live events  │   └────────────┘
 │          │──────────▶│ scheduled jobs · push   │──▶ Redis (only with 2+ instances)
 └──────────┘  everything└─────────────────────────┘──▶ OpenStreetMap services
      │         else             │                    (search, routes, ranks)
      ▼                          ├──▶ email (SMTP) · SMS · Web Push
 ┌──────────────────┐            └──▶ on-call webhook (panic alerts)
 │ frontend (Next.js│
 │ PWA)             │   Prometheus · Alertmanager · Grafana · daily backups
 └──────────────────┘
```

In production one domain serves both: Caddy sends `/api/*` to the backend and
everything else to the web app. The web app's session lives in httpOnly
cookies, and screens update live over server-sent events.

## Repositories

| Repository | What's in it |
|---|---|
| [e-tekisi-backend](https://github.com/Ranwedzi/e-tekisi-backend) (this one) | API, database migrations, Docker Compose files (dev, production, cluster, maps, e2e), Caddy, monitoring, backups, load tests, docs |
| [e-tekisi-frontend](https://github.com/Ranwedzi/e-tekisi-frontend) | Next.js web app for commuters, drivers and operators, installable on phones; browser tests |

## Quick start (Docker)

You need Docker and the two repositories side by side:

```bash
git clone https://github.com/Ranwedzi/e-tekisi-backend.git
git clone https://github.com/Ranwedzi/e-tekisi-frontend.git
cd e-tekisi-backend
cp .env.example .env
```

In `.env`, set `FRONTEND_DIR=../e-tekisi-frontend`. To be able to use the
operator console, also set `ADMIN_BOOTSTRAP_EMAIL` and
`ADMIN_BOOTSTRAP_PASSWORD` (12+ characters). Then:

```bash
docker compose up -d --build
```

| Address | What |
|---|---|
| http://localhost:3000 | the app |
| http://localhost:8080/swagger-ui.html | every API endpoint |
| http://localhost:8025 | Mailpit: every email the backend sends (verification links, password resets) |

The database is Postgres on port 5433, with its data in `./postgres_data`.
After code changes, run `docker compose up -d --build` again; stop
everything with `docker compose down`.

### Try a ride

1. Sign up as a commuter and click the verification link in Mailpit.
2. On the wallet page, top up R100. Dev mode uses a sandbox, so no real money
   moves.
3. In another browser (or a private window), sign up as a driver.
4. Sign in to `/login` → "e-Tekisi staff?" with the bootstrap operator,
   accept the driver's documents and approve the driver.
5. The driver goes online. The commuter requests a taxi, and the driver
   offers a fare.
6. The commuter accepts and pays. The driver taps "I've arrived", then
   "start trip", then completes the trip.
7. Both rate each other, and the driver requests a payout, which the
   operator approves.

## Develop without Docker

You need Java 21+, Node 20+, Docker (for Postgres), and ports 5433, 8080
and 3000 free.

```bash
cp .env.example .env
docker compose up -d db mailpit        # database and email catcher
cd e-tekisi && ./mvnw spring-boot:run  # API on :8080
```

On start, Flyway creates or upgrades every table
(`e-tekisi/src/main/resources/db/migration`). Hibernate then checks that the
code matches the schema. `http://localhost:8080/actuator/health` should say
`UP`. For the web app, see the
[frontend README](https://github.com/Ranwedzi/e-tekisi-frontend#readme).

## Project layout

```
e-tekisi/                     the Spring Boot backend
  src/main/java/com/etekisi/etekisi/
    controller/               REST endpoints
    service/                  bookings, wallet and ledger, payouts, limits, reconciliation, accounts
    session/                  cookie sessions and refresh tokens
    push/                     Web Push (encryption, VAPID keys, subscriptions)
    realtime/                 live events over SSE
    cluster/                  shared state for several instances (Redis)
    agent/                    advisory services: dispatch hints, ETAs, safety monitoring,
                              trip anomalies, payment risk, demand hotspots, audit trail
    model/ repository/ dto/   entities, data access, API shapes
  src/main/resources/db/migration/   Flyway migrations (V1–V24)
  src/test/                   unit and integration tests
deploy/                       Caddyfile, backup job, cluster and monitoring config
monitoring/                   Prometheus alert rules
perf/                         k6 load tests and results
scripts/                      CI e2e runner, VAPID key generator, maps check
docs/                         deployment, backups, API contract, maps, decisions (adr/)
docker-compose.yml            local development
docker-compose.prod.yml       production / staging (HTTPS, monitoring, backups)
docker-compose.cluster.yml    two backend instances behind a load balancer
docker-compose.maps.yml       self-hosted map services (optional)
docker-compose.e2e.yml        the stack CI runs browser tests against
```

## Tech stack

| Part | Built with |
|---|---|
| Backend | Java 21, Spring Boot 4, Spring Security (JWT), JPA/Hibernate, Flyway, ShedLock |
| Database | PostgreSQL (16 in production, 15 in the local Docker setup); Redis when running more than one instance |
| Web app | Next.js 16, React 19, TypeScript, Tailwind CSS 4, Leaflet maps |
| Real time | Server-sent events; Web Push (RFC 8291/8292) for notifications with the app closed |
| Maps | OpenStreetMap: Nominatim (search), OSRM (routes), Overpass (taxi ranks), map tiles |
| Tests | JUnit 5, MockMvc, Testcontainers; Vitest; Playwright; k6 for load |
| Operations | Docker Compose, Caddy (automatic HTTPS), Prometheus, Alertmanager, Grafana, GitHub Actions |

## How the important parts work

**Accounts and security**
- Every endpoint takes the caller's identity from the session. The caller
  can only see and change their own data; ids sent in request bodies are
  ignored.
- The web app's session is in httpOnly, SameSite=Strict cookies: a
  15-minute access token and a rotating refresh token. Copying an old
  refresh token ends the session, and "sign out everywhere" is on the
  profile.
- API clients can use `Authorization: Bearer` instead.
- Email or SMS verification, password reset, account lockout after repeated
  wrong passwords, and two-step sign-in (authenticator app) for staff.

**Money**
- Amounts are exact decimals.
- Every balance change is a row in the ledger
  (`commuter_schema.wallet_transactions`), written in a transaction with row
  locks, and retried requests can't charge twice.
- The commuter is charged only when they accept a driver's offer. The
  driver is paid when the trip completes. Cancelling an accepted trip
  refunds the commuter.
- Daily limits: top-ups, payouts, and how much a wallet can hold. Risky
  payouts wait for an operator, and a daily reconciliation checks that every
  balance matches the ledger.
- Top-ups only go through a sandbox for now. Production refuses them until
  the Paystack integration is built.

**Trips**
- A request is open to drivers for 15 minutes and then expires (nothing is
  charged).
- A scheduled trip is shown to drivers 30 minutes before departure.
- An offer lapses after 2 minutes if the commuter doesn't answer.
- All of these times are settings (`tekisi.booking.*`).

**Safety**
- The panic button pages on-call (webhook or PagerDuty) and stays on the
  console until someone acknowledges it.
- "Share my trip" gives family a live link that stops working when the trip
  ends.

**Privacy (POPIA)**
- People accept the terms at sign-up, can download their data, and can
  delete their account; deletion keeps anonymised trip and money records.
- Commuters only see rounded taxi positions nearby.
- GPS, chat and notification data is deleted after a set time.

**Maps**
- The app uses the public OpenStreetMap servers, which is fine for a pilot.
  The backend limits itself to one request a second to each and caches the
  answers.
- Switch to a hosted provider, or self-host with
  `docker-compose.maps.yml`, before a public launch (see `docs/MAPS.md`).

## Configuration

Settings come from environment variables:
- `e-tekisi/src/main/resources/application.properties` holds the dev
  defaults;
- `application-prod.properties` requires the secrets and has no defaults.

Copy `.env.prod.example` (or `.env.staging.example`) for a server.

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Postgres connection |
| `JWT_SIGNING_SECRET` (≥ 32 bytes), `JWT_ISSUER`, `JWT_EXPIRY_MINUTES` | tokens; `JWT_EXPIRY_MINUTES` is for API clients using Bearer tokens |
| `SESSION_ACCESS_MINUTES` (15), `SESSION_REFRESH_DAYS` (30), `SESSION_MAX_DAYS` (90), `SESSION_STAFF_HOURS` (12), `SESSION_SECURE_COOKIES` (true) | web-app sessions in httpOnly cookies. Secure cookies need https (localhost is fine) |
| `CORS_ALLOWED_ORIGINS` | frontend origins, comma-separated |
| `ADMIN_BOOTSTRAP_EMAIL`, `ADMIN_BOOTSTRAP_PASSWORD` | first operator account, created once (signs in at `/login` → "e-Tekisi staff?", lands on `/ops`) |
| `DATA_ENCRYPTION_KEY` | base64 of 32 random bytes (`openssl rand -base64 32`); encrypts payout bank details. Required in prod; **back it up**, losing it makes those columns unreadable |
| `PAYMENTS_SANDBOX_ENABLED` | dev top-ups (false in prod) |
| `RETENTION_TRIP_GPS_DAYS` (90), `RETENTION_DRIVER_LOCATION_DAYS` (7), `RETENTION_MESSAGES_DAYS` (180), `RETENTION_NOTIFICATIONS_DAYS` (90), `AUDIT_RETENTION_DAYS` (90) | how long personal data is kept; 0 keeps it. GPS of trips with a critical safety alert is kept |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `MAIL_FROM` | SMTP for verification and password-reset emails (required in prod; dev uses Mailpit) |
| `SMS_PROVIDER` (`none`, `log`, `bulksms`, `clickatell`, `twilio`, `generic`), `SMS_URL`, `SMS_USERNAME`, `SMS_PASSWORD`, `SMS_FROM` | SMS codes for phone verification and reset (optional) |
| `PUBLIC_URL`, `LEGAL_VERSION` | the web app's address for email links; the version of the terms people accept (bump it when the documents change) |
| `DRIVER_REQUIRED_DOCUMENTS` (`DRIVERS_LICENCE,PRDP,OPERATING_LICENCE`) | documents an operator must accept before approving a driver |
| `STAFF_MFA_REQUIRED` (prod `true`, dev `false`) | operators must use an authenticator-app code at sign-in |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` | push notifications. Make the pair once with `sh scripts/vapid-keys.sh` and keep it (new keys break every subscription). Unset: dev makes temporary keys, prod and staging turn push off |
| `SAFETY_ALERT_WEBHOOK`, `SAFETY_ALERT_WEBHOOK_FORMAT`, `SAFETY_ALERT_WEBHOOK_SECRET` | pages on-call for panic alerts (generic JSON or PagerDuty) |
| `REDIS_ENABLED`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | shared state and live events for more than one backend instance (`perf/CLUSTER.md`) |
| `LOGIN_IP_REQUESTS_PER_MINUTE` (60) | per-IP limit on sign-in, sign-up and reset; account lockout after 5 wrong passwords is separate |
| `METRICS_USERNAME`, `METRICS_PASSWORD` | HTTP Basic for the Prometheus scraper on `/actuator/prometheus` |
| `OSM_NOMINATIM_URL`, `OSM_OSRM_URL`, `OSM_OVERPASS_URL` | map services; leave unset for the public servers, or point at your own (`docs/MAPS.md`) |

## Tests

```bash
cd e-tekisi
./mvnw test
```

219 tests. Integration tests run on H2. The Postgres tests
(`FlywayMigrationTest`, `SchemaValidationTest`,
`LegacyUpgradeMigrationTest`) need Docker and are skipped without it.
`LlmIntentGoldenEvalTest` needs an AI API key and is skipped otherwise.

The web app has its own unit tests (Vitest) and browser tests (Playwright);
see its README.

On GitHub, CI builds and tests the backend, runs a dependency vulnerability
check and a secret scan, and builds the Docker image. A full-stack browser
test job runs once the repository variable `FRONTEND_REPOSITORY` is set
(`Ranwedzi/e-tekisi-frontend`).

## Deployment

| Setup | How |
|---|---|
| One production server with HTTPS | `docker-compose.prod.yml` + `.env.prod`; step by step in `docs/DEPLOY.md` |
| Staging | the same compose file with `.env.staging` (see `docs/DEPLOY.md`) |
| Two or more backend instances | `docker-compose.cluster.yml`; see `perf/CLUSTER.md` (load test: 2,544 trips in 2 minutes, p95 173 ms, no errors) |
| Your own map services | `docker-compose.maps.yml`; see `docs/MAPS.md` (needs about 100 GB of disk) |

## Documentation

| Document | Covers |
|---|---|
| `docs/DEPLOY.md` | production and staging, updates, rollback, CI set-up |
| `docs/BACKUP_RESTORE.md` | daily backups and the restore drill |
| `docs/FRONTEND_INTEGRATION.md` | the API contract the web app codes against |
| `docs/MAPS.md` | map services and self-hosting them |
| `docs/adr/` | recorded design decisions |
| `perf/CLUSTER.md` | running several instances, load-test results |
| [Frontend `TRANSLATIONS.md`](https://github.com/Ranwedzi/e-tekisi-frontend/blob/main/TRANSLATIONS.md) | languages and how to review them |

## Project status

**Done:**
- the full ride flow;
- accounts and security;
- the wallet with its ledger, limits and reconciliation;
- payouts with operator approval;
- driver vetting;
- safety alerts and trip sharing;
- POPIA features;
- live updates and push notifications;
- the installable app and five languages;
- monitoring, backups, staging and CI.

**Still to do in code**, in order:
1. Paystack top-ups.
2. Automated driver payouts.
3. Receipts, refunds and disputes.
4. Translated notification texts. Messages written by the backend are
   English only.
5. A low-data mode.
6. An accessibility review.
7. A hosted map provider before public launch.

**Still to do outside the code:**
- a lawyer's review of the terms, privacy policy and driver agreement, and
  of holding money in a wallet;
- registering a POPIA Information Officer;
- a penetration test;
- staging and production servers;
- email, SMS and paging providers, and an on-call rota;
- native-speaker review of the translations.

## Security notes

- Never commit `.env`, `.env.prod` or `.env.staging`; they are gitignored.
- Keep `DATA_ENCRYPTION_KEY` and the VAPID keys in a password manager or
  vault as well as on the server.
- An old local database password was committed in early versions of
  `docker-compose.yml`. It isn't used any more; treat it as public and never
  reuse it.

## Licence

No licence has been chosen yet. The code is public to read, but all rights
are reserved until a `LICENSE` file is added.
