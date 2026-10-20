# e-Tekisi backend

Spring Boot 4 (Java 21) API for e-Tekisi, a minibus-taxi booking app:
commuters book and pay from a wallet, drivers set the fare, accept and
complete trips and cash out, and operators watch an advisory "agent" layer
(dispatch suggestions, safety alerts, payment-risk flags).

The frontend lives in a separate repo (`e-tekisi-frontend`).

## Run everything in Docker

With Docker only (no Java or Node needed), and the frontend repo checked
out next to this one (`../../e-tekisi-frontend-main/e-tekisi-frontend-main`,
or set `FRONTEND_DIR` in `.env`):

```bash
cp .env.example .env
docker compose up -d --build
```

Open `http://localhost:3000`. The backend is on `:8080` (dev profile, so
sandbox top-ups work) and Postgres on `:5433`, with data kept in
`./postgres_data`. Stop with `docker compose down`; after code changes,
run `docker compose up -d --build` again. Production uses
`docker-compose.prod.yml` (see below).

## Production (single server, HTTPS)

`docker-compose.prod.yml` runs Caddy, the frontend, the backend (prod
profile) and Postgres, plus daily backups and Prometheus, Alertmanager and
Grafana (localhost only, reach them over SSH). Caddy gets a Let's Encrypt certificate for your
domain and serves the site and the API from the same origin.

```bash
cp .env.prod.example .env.prod     # fill in every value, then: chmod 600 .env.prod
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
```

Point the domain's DNS at the server and open ports 80 and 443 first. Only
Caddy is exposed; Swagger and metrics aren't routed publicly. See
`docs/DEPLOY.md` for updates, backups and rollback.

## Run it locally

You need Java 21+, Docker, and ports 5433 and 8080 free.

1. Start Postgres:

   ```bash
   cp .env.example .env
   docker compose up -d db
   ```

   `.env` holds the local database credentials (it is gitignored). The
   backend's dev defaults match `.env.example`, so nothing else is needed.
   If you change the password, also export `DB_PASSWORD` before step 2.

2. Start the API:

   ```bash
   cd e-tekisi
   ./mvnw spring-boot:run
   ```

   On startup Flyway creates or upgrades every table
   (`e-tekisi/src/main/resources/db/migration`), then Hibernate checks the
   entities match (`ddl-auto=validate`).

3. Check it: `http://localhost:8080/actuator/health` returns `UP`, and
   `http://localhost:8080/swagger-ui.html` shows every endpoint.

## Accounts and roles

| Role | How to get one | Can |
|---|---|---|
| COMMUTER | `POST /api/auth/register/commuter` or the app's sign-up | book, pay, chat, cancel, panic |
| DRIVER | `POST /api/auth/register/driver` or the app's sign-up | go online, accept/complete trips, queue, cash out |
| OPERATOR / ADMIN | set `ADMIN_BOOTSTRAP_EMAIL` and `ADMIN_BOOTSTRAP_PASSWORD` (12+ chars) before starting; created once | insights, dispatch, agent endpoints |

Log in with `POST /api/auth/login` `{email, password, role}` and send the
returned token as `Authorization: Bearer <token>`. Every endpoint checks the
caller owns what they ask for; ids in request bodies are ignored.

A quick local walkthrough uses made-up accounts such as
`lindi.commuter@example.test` / `sipho.driver@example.test` with any
8+ character password: sign both up, top up the commuter's wallet, book a
trip as the commuter, accept it as the driver, complete it, request a payout.

## Money

- Wallet top-ups go through a `PaymentProvider`. Only the **sandbox**
  provider exists (no real money moves); it is on in dev and **off in the
  prod profile**, where top-up returns 403 until a real gateway (Yoco,
  PayFast, Paystack, …) implements `PaymentProvider`.
- The commuter is charged fare + tip when a driver accepts; the driver is
  credited on complete; cancelling an accepted trip refunds the commuter.
- Every balance change writes a row to `commuter_schema.wallet_transactions`.
- Drivers **offer** a fare; the commuter is only charged when they accept
  it. Offers lapse after 2 minutes (`tekisi.booking.offer-ttl-seconds`).
- Payouts: the amount leaves the driver's balance on request; operators then
  approve and mark it paid (with the bank reference) or reject it, which
  refunds the driver. Bank details are encrypted at rest.
- Limits (config in `application.properties`): fare ≤ R1000, top-up
  R10–R2000, payout R20–R5000.

## Configuration

Everything sensitive comes from environment variables; see
`e-tekisi/src/main/resources/application.properties` (dev defaults) and
`application-prod.properties` (prod requires them, no defaults).

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Postgres connection |
| `JWT_SIGNING_SECRET` (≥ 32 bytes), `JWT_ISSUER`, `JWT_EXPIRY_MINUTES` | tokens |
| `CORS_ALLOWED_ORIGINS` | frontend origins, comma-separated |
| `ADMIN_BOOTSTRAP_EMAIL`, `ADMIN_BOOTSTRAP_PASSWORD` | first operator account (signs in at `/login` → "e-Tekisi staff?", lands on `/ops`) |
| `DATA_ENCRYPTION_KEY` | base64 of 32 random bytes (`openssl rand -base64 32`); encrypts payout bank details. Required in prod; **back it up**, losing it makes those columns unreadable |
| `PAYMENTS_SANDBOX_ENABLED` | dev top-ups (false in prod) |
| `RETENTION_TRIP_GPS_DAYS` (90), `RETENTION_DRIVER_LOCATION_DAYS` (7), `RETENTION_MESSAGES_DAYS` (180), `RETENTION_NOTIFICATIONS_DAYS` (90), `AUDIT_RETENTION_DAYS` (90) | how long personal data is kept; a daily job at 03:30 deletes older rows, 0 turns one off. GPS of trips with a CRITICAL safety alert is kept |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `MAIL_FROM` | SMTP for verification and password-reset emails (required in prod; dev uses Mailpit at http://localhost:8025) |
| `SMS_PROVIDER` (`none`, `log`, `bulksms`, `clickatell`, `twilio`, `generic`), `SMS_URL`, `SMS_USERNAME`, `SMS_PASSWORD`, `SMS_FROM` | SMS codes for phone verification and reset (optional) |
| `PUBLIC_URL`, `LEGAL_VERSION` | frontend address used in email links; version of the terms people accept (bump it when the documents change) |
| `LOGIN_IP_REQUESTS_PER_MINUTE` (60) | per-IP limit on sign-in/sign-up/reset; account lockout after 5 wrong passwords is separate |
| `SAFETY_ALERT_WEBHOOK`, `SAFETY_ALERT_WEBHOOK_FORMAT`, `SAFETY_ALERT_WEBHOOK_SECRET` | pages on-call for panic-button alerts (generic JSON or PagerDuty) |
| `METRICS_USERNAME`, `METRICS_PASSWORD` | HTTP Basic for the Prometheus scraper on `/actuator/prometheus` |
| `OSM_NOMINATIM_URL`, `OSM_OSRM_URL`, `OSM_OVERPASS_URL` | map services; point at self-hosted instances for production traffic (the public servers allow ~1 request/second, which the app enforces and caches around) |

## Tests

```bash
cd e-tekisi
./mvnw test
```

Integration tests run on H2. The Postgres tests (`FlywayMigrationTest`,
`SchemaValidationTest`, `LegacyUpgradeMigrationTest`) need Docker and are
skipped without it. `LlmIntentGoldenEvalTest` needs an API key and is
skipped otherwise.

## More docs

- `docs/FRONTEND_INTEGRATION.md` — API contract for the frontend
- `docs/DEPLOY.md` — production deployment (`docker-compose.prod.yml` + `deploy/Caddyfile`)
- `docs/BACKUP_RESTORE.md` — database backups and the restore drill

## Personal data (POPIA)

- Signed-in commuters and drivers can download everything held about them
  (`GET /api/me/export`, "Download my data" on the profile page) and delete
  their account (`POST /api/me/delete`). Deletion anonymises the account and
  keeps trips and ledger rows for the financial record.
- Location: `/api/locations/nearby` only returns rounded positions near the
  caller, with no driver ids.
- Retention: see the `RETENTION_*` settings above.

## Security notes

- The old local database password was committed in
  earlier versions of `docker-compose.yml`. It is no longer used; treat it
  as public and never reuse it.
- `.DS_Store`, `.idea/` and `target/` are ignored by git.
