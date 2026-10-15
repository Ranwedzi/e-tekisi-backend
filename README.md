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
`e-tekisi/docker-compose.prod.yml`.

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
- `docs/DEPLOY.md` — production deployment (`e-tekisi/docker-compose.prod.yml`)
- `docs/BACKUP_RESTORE.md` — database backups

## Security notes

- The old local database password was committed in
  earlier versions of `docker-compose.yml`. It is no longer used; treat it
  as public and never reuse it.
- `.DS_Store`, `.idea/` and `target/` are ignored by git.
