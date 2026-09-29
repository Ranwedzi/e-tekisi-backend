# Frontend Integration Guide

> Status: backend is **frontend-ready** as of the changes documented here.
> Phases 1–3 of the production roadmap are complete; this document covers
> the four pre-flight fixes that unblock the frontend wireup, and the
> contract the frontend should code against.

---

## 1. What changed and why

The frontend would have hit four predictable walls on day one. Each was
fixed in its own small commit:

### 1.1  `POST /api/bookings/create` now returns JSON, not a string

**Before:**

```http
POST /api/bookings/create
< 200 OK
< Content-Type: text/plain
< Booking saved! Waiting for a driver to set the fare.
```

The frontend had no booking ID to chain into `accept` / `complete` /
`cancel`. It would have to do a `GET /api/bookings/user/{id}` and grep for
the new row by timestamp — fragile and racy.

**After:**

```http
POST /api/bookings/create
< 200 OK
< Content-Type: application/json
< {
<   "status": "PENDING",
<   "bookingId": 4123,
<   "booking": { "id": 4123, "commuterId": 101, "pickupLocation": "Bree",
<                "destination": "Sandton", "fare": null, "tip": null,
<                "status": "PENDING", "scheduledTime": null, "driverId": null },
<   "message": "Booking saved. Waiting for a driver to set the fare."
< }
```

`BookingController.createBooking()` returns a `Map<String,Object>` carrying
the new `bookingId`, the full projected `BookingDto`, and a human-readable
message. Idempotency-Key behavior is unchanged — the same key returns the
same envelope verbatim.

**Tests affected:** none. `BookingControllerEventTest` asserts on the
published `BookingCreatedEvent`, not on the HTTP body. All 71 tests still
pass after the change.

---

### 1.2  OpenAPI / Swagger UI is now served

Added `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0` and an
`OpenApiConfig` bean that declares the bearer-JWT scheme. SecurityConfig
explicitly permits the documentation paths.

**Frontend devs hit:**

| URL | Purpose |
|---|---|
| `GET /swagger-ui.html` | Interactive console. Click "Authorize" → paste a token from `/api/auth/login` → exercise gated endpoints in-browser. |
| `GET /v3/api-docs` | Raw OpenAPI 3 JSON. Feed into your codegen (`openapi-typescript`, `orval`, etc.) to mint a typed client. |

**Production posture:** Swagger UI is authenticated-by-default at the
network layer in real prod (lock it behind the reverse proxy IP allow-list
or a basic-auth realm). The app-level allow-list lets staging be open for
the wireup phase.

---

### 1.3  JPA entities no longer leak through controller responses

Three bounded API records were added under `com.etekisi.etekisi.dto.api`:

| Record | Source entity | Notable omissions |
|---|---|---|
| `BookingDto` | `Booking` | Hibernate proxy metadata, lazy-init hooks. |
| `DriverDto` | `Driver` | **bcrypt password hash** (security-critical). |
| `CommuterDto` | `User` | **bcrypt password hash** (security-critical). |

**Endpoints projected:**

`BookingController`:
- `GET /api/bookings/pending` → `List<BookingDto>`
- `GET /api/bookings/pending/{driverId}` → `List<BookingDto>`
- `GET /api/bookings/user/{userId}` → `List<BookingDto>`
- `GET /api/bookings/driver/{driverId}` → `List<BookingDto>`
- `GET /api/bookings/driver/{driverId}/earnings` → `history` field is now
  `List<BookingDto>` (was `List<Booking>`)

`RegistrationController`:
- `GET /api/auth/commuter/{id}` → `CommuterDto`
- `GET /api/auth/driver/{id}` → `DriverDto`
- `PUT /api/auth/driver/{id}/status` → `DriverDto`
- `PUT /api/auth/driver/{id}/complete-trip` → `DriverDto`

**The pre-existing security leak fixed by this:** `getCommuter` and
`getDriver` were returning the bcrypt password hash in the response body.
Anyone with read access to those endpoints (which were `permitAll()`
before Phase 1.1, and still readable by any authenticated user now) could
have lifted hashes for offline cracking. Now stripped at the projection
boundary.

**Why a record, not a class:** the API contract becomes immutable by
construction, and `BookingDto.from(...)` factories make the projection
explicit at every callsite. Adding a field to the entity does NOT
silently leak it through the API.

---

### 1.4  Auth posture (no change, just clarifying)

The backend defaults to `tekisi.security.mode=jwt`. Two practical
implications for the frontend dev environment:

- `JWT_SIGNING_SECRET` must be ≥ 32 bytes. The dev fallback in
  `application.properties` (`dev-only-do-not-use-in-prod-min-32-bytes-xxx`)
  satisfies this; the prod profile drops it so the app fails fast on a
  missing env var.
- For initial wireup of static screens, you can flip to permit-all mode:
  ```bash
  TEKISI_SECURITY_MODE=permit-all ./mvnw spring-boot:run
  ```
  Flip back to `jwt` before integrating the auth flow.

CORS already accepts `http://localhost:3000`, `:3001`, `:5173` by
default. Add your dev origin to `tekisi.cors.allowed-origins` (or env
`CORS_ALLOWED_ORIGINS`) if it differs.

---

## 2. The frontend integration playbook

A vertical slice in this order:

### Step 1 — Boot the backend

```bash
cd e-tekisi
./mvnw spring-boot:run
# or, for a real container:
docker compose -f docker-compose.prod.yml up
```

Visit `http://localhost:8080/actuator/health` → `{"status":"UP"}`.
Visit `http://localhost:8080/swagger-ui.html` → interactive console.

### Step 2 — Register a test user and log in

```bash
# Register a commuter
curl -X POST http://localhost:8080/api/auth/register/commuter \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Test Commuter","email":"c@test.dev","phoneNumber":"+27123456789","password":"password123"}'
# < {"status":"REGISTERED","userId":1,"role":"COMMUTER"}

# Log in
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"c@test.dev","password":"password123","role":"COMMUTER"}'
# < {"status":"SUCCESS","userId":1,"role":"COMMUTER",
# <  "token":"eyJ…","tokenType":"Bearer","expiresInSeconds":3600}
```

Store the token in your frontend (memory + httpOnly cookie if SSR;
sessionStorage if SPA-only). Send `Authorization: Bearer <token>` on
every subsequent request.

### Step 3 — Wire login → token storage → first authenticated call

The first vertical slice should be: **log in → POST a booking → see the
audit row land**. Verify it round-trips by:

```bash
curl http://localhost:8080/actuator/prometheus | grep tekisi_bookings_created_total
# Should increment by 1 per successful create.
```

And in Postgres (or the H2 console):

```sql
SELECT correlation_id, agent, confidence_level, fallback_used
  FROM agent_schema.decision_audit
  ORDER BY computed_at DESC LIMIT 5;
```

Each call should produce one row with the same `correlation-id` you sent
in the `X-Correlation-Id` header.

### Step 4 — Generate a typed client

```bash
# pick one
npx openapi-typescript http://localhost:8080/v3/api-docs -o src/api/types.ts
npx orval --input http://localhost:8080/v3/api-docs --output src/api
```

Treat the generated client as the contract. Don't hand-write fetch
wrappers if you can avoid it — drift between frontend types and backend
JSON is the most common source of bugs in this kind of project.

---

## 3. The contract the frontend should code against

### 3.1 Auth

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/auth/register/commuter` | `User` (fullName, email, phoneNumber, password) | `{status, userId, role}` |
| POST | `/api/auth/register/driver` | `Driver` (+ taxiPlate, licenseNumber) | `{status, userId, role}` |
| POST | `/api/auth/login` | `{email, password, role}` | `{status, userId, role, token, tokenType, expiresInSeconds}` on 200; `{status:"FAIL", message}` on 401 |
| GET | `/api/auth/commuter/{id}` | — | `CommuterDto` |
| GET | `/api/auth/driver/{id}` | — | `DriverDto` |

### 3.2 Bookings (commuter + driver)

| Method | Path | Auth | Returns |
|---|---|---|---|
| POST | `/api/bookings/create` | commuter | `{status, bookingId, booking, message}` |
| GET | `/api/bookings/pending` | driver | `List<BookingDto>` |
| GET | `/api/bookings/pending/{driverId}` | driver | `List<BookingDto>` (excludes declined) |
| GET | `/api/bookings/user/{userId}` | commuter | `List<BookingDto>` |
| GET | `/api/bookings/driver/{driverId}` | driver | `List<BookingDto>` |
| GET | `/api/bookings/driver/{driverId}/earnings` | driver | `{totalEarnings, tripCount, history, availableBalance}` |
| PUT | `/api/bookings/{bookingId}/accept/{driverId}?fare=…` | driver | `{status, fare, driverName, driverPhone, taxiPlate, commuterName, commuterPhone}` |
| POST | `/api/bookings/{bookingId}/decline/{driverId}` | driver | `{status:"DECLINED"}` |
| PUT | `/api/bookings/{bookingId}/complete/{driverId}` | driver | `{status, earned}` |
| PUT | `/api/bookings/{bookingId}/cancel?role=…` | commuter or driver | text |

**Idempotency:** `POST /create`, `PUT /accept`, `PUT /complete` all
honour `Idempotency-Key`. On flaky networks, use a UUID per logical
intent and retry safely.

**Correlation-ID:** every endpoint accepts `X-Correlation-Id`; if
omitted, the backend generates one and echoes it back. Always log the
echoed value on the frontend so support can grep audit rows.

### 3.3 Wallet & payouts

| Method | Path | Auth | Returns |
|---|---|---|---|
| GET | `/api/wallet/{userId}?role=COMMUTER\|DRIVER` | self | `Wallet` |
| POST | `/api/wallet/{userId}/topup?role=…&amount=…` | self | `Wallet` (advisory: payment-risk insight may be persisted) |
| GET | `/api/payouts/driver/{driverId}` | driver | `List<PayoutRequest>` |
| POST | `/api/payouts/driver/{driverId}/request` | driver | `{status, requestId}` or `{status:"INSUFFICIENT_FUNDS", available}` |

### 3.4 Operator / agent surface

Locked to `ROLE_OPERATOR` or `ROLE_ADMIN` in `SecurityConfig`.

| Method | Path | Returns |
|---|---|---|
| GET | `/api/insights?severity=&code=&bookingId=&since=&page=&size=` | `{items, page, size, totalElements, totalPages}` |
| POST | `/api/agents/dispatch` | `DispatchDecision` |
| POST | `/api/agents/dispatch/auto` | `{decision, autonomous, recommendationStatus, …}` |
| POST | `/api/agents/safety/panic?bookingId=…` | `SafetyAlert` (also fans out via `OpsAlertListener`) |
| GET | `/api/agents/hotspots` | `HotspotRecommendation` |

---

## 4. Error envelope (RFC 7807)

Every controller error returns a uniform `application/problem+json`:

```json
{
  "type": "https://tekisi.dev/problems/validation-failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed for one or more fields.",
  "correlationId": "8b6b9f23-d08c-4f3d-9279-9c1b9a11de82",
  "fieldErrors": {
    "email":      "must be a well-formed email address",
    "phoneNumber":"phoneNumber must be 8-20 digits/+/-/space/parentheses"
  }
}
```

Frontend should:

- Read `status` and `detail` for the top-level toast.
- Read `fieldErrors` (when present) to highlight individual form fields.
- Log `correlationId` in the browser console — it's the join key with the
  backend audit + Prometheus dashboards.

---

## 5. Local dev checklist

- [ ] Backend boots clean: `mvn spring-boot:run` → no stack traces.
- [ ] `curl http://localhost:8080/actuator/health` returns `UP`.
- [ ] `http://localhost:8080/swagger-ui.html` loads.
- [ ] `JWT_SIGNING_SECRET` ≥ 32 bytes (dev fallback covers this).
- [ ] Frontend dev origin is in `CORS_ALLOWED_ORIGINS`.
- [ ] Generated TypeScript client compiles against `BookingDto`,
      `DriverDto`, `CommuterDto`.
- [ ] First end-to-end booking lands a row in `agent_schema.decision_audit`
      with the same correlation-id seen in the browser network tab.
- [ ] Token expiry handled — the frontend refreshes via re-login (no
      refresh-token flow yet; see Phase 4 for that).

---

## 6. What you're NOT doing yet (Phase 3 follow-ups)

These are intentionally not wired into the frontend yet — the backend
exposes them but they're behind canary flags or pending operator
training:

- **Autonomous dispatch** (`POST /api/agents/dispatch/auto`) — backend is
  ready but `tekisi.agent.dispatch.autonomous-enabled=false` by default.
  Until the canary plan from `docs/DEPLOY.md` is signed off, the frontend
  ops console should call `/api/agents/dispatch` (advisory) instead.
- **LLM intent** (`POST /api/agents/intent/interpret`) — backend client
  exists but is gated by `tekisi.agent.intent.llm-enabled=false`. The
  deterministic `RankResolver` is the source of truth; LLM is a candidate
  layer only.
- **Polyline route-deviation** — the `agent_schema.trip_routes` table is
  populated only when an external routing API is wired (Phase 2.1
  Resilience4j config + a real OSRM endpoint). Without it, safety
  evaluation falls back to `ALWAYS_ON_PATH` — no false alarms, but no
  route-deviation alerts either.
- **Hotspot time-series** — `DemandPulseAggregator` writes per-minute
  buckets; the frontend "where is demand" feature can read
  `GET /api/agents/hotspots` once 15 minutes of pulses exist.

---

## 7. Files changed in this pass

```
e-tekisi/pom.xml                                         (+ springdoc dep)
e-tekisi/src/main/java/com/etekisi/etekisi/
  config/OpenApiConfig.java                              (new)
  config/SecurityConfig.java                             (permit swagger paths)
  controller/BookingController.java                      (DTO projections + create returns JSON)
  controller/RegistrationController.java                 (DTO projections, password leak fixed)
  dto/api/BookingDto.java                                (new)
  dto/api/CommuterDto.java                               (new)
  dto/api/DriverDto.java                                 (new)
docs/FRONTEND_INTEGRATION.md                             (this file)
```

All 71 backend tests pass; the two skips are deliberate gates
(Testcontainers no-Docker locally, LLM golden-eval no-API-key locally),
both exercised in CI.

---

## 8. When to come back to this document

- **First-week wireup**: read sections 1–3.
- **When generating the typed client**: section 2 step 4 + section 3.
- **When debugging a 4xx**: section 4 (error envelope shape).
- **When promoting from staging to prod**: section 1.4 (auth posture)
  and `docs/DEPLOY.md` (deploy procedure).
- **When deciding what feature to wire next**: section 6 (what's intentionally
  not exposed yet).
