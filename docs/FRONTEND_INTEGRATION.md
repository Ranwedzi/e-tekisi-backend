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

### 1.4  Auth posture

Every endpoint except sign-up, login, health and the OpenAPI docs needs a
valid JWT. **There is no permit-all mode any more** (it was removed because
the frontend had been developed against it and never sent the token).

- `JWT_SIGNING_SECRET` must be ≥ 32 bytes. The dev fallback in
  `application.properties` satisfies this; the prod profile drops it so the
  app fails fast on a missing env var.
- **Ownership is enforced server-side.** The caller's id and role come from
  the token (`sub`, `role`). Ids in the URL are checked against it (403 if
  they don't match) and ids in request bodies (`commuterId`, `driverId`,
  `senderId`) are ignored. Commuter and driver ids come from separate
  tables, so the same number can be both; the role is what tells them apart.

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
docker compose up -d --build          # from the repo root: db + backend + frontend
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

"self" means the id in the path must be the caller's own (staff may read any).

### 3.1 Auth

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/auth/register/commuter` | `{fullName, email, phoneNumber, password}` (password ≥ 8) | `{status, userId, role}`; 409 `EMAIL_TAKEN` |
| POST | `/api/auth/register/driver` | as above + `taxiPlate`, `licenseNumber` (plate is upper-cased, spaces/dashes removed) | `{status, userId, role, approvalStatus:"PENDING_APPROVAL"}`; 409 `EMAIL_TAKEN`. The driver can't go online, offer or queue until an operator approves them |
| POST | `/api/auth/login` | `{email, password, role}` (role: COMMUTER, DRIVER, OPERATOR, ADMIN) | `{status, userId, role, token, tokenType, expiresInSeconds}` on 200; `{status:"FAIL", message}` on 401; 429 `TOO_MANY_ATTEMPTS` with `retryAfterSeconds` after 5 wrong passwords (15-minute lock) or more than 20 auth requests a minute from one IP |
| GET | `/api/auth/commuter/{id}` | — (self) | `CommuterDto` |
| GET | `/api/auth/driver/{id}` | — (self) | `DriverDto` (`active` is the online flag) |
| PUT | `/api/auth/driver/{id}/status?active=true\|false` | — (self) | `DriverDto`. Going online needs an approved account: 409 `DRIVER_NOT_APPROVED` otherwise |

### 3.2 Bookings (commuter + driver)

| Method | Path | Auth | Returns |
|---|---|---|---|
| POST | `/api/bookings/create` | commuter; body `{pickupLocation, destination, scheduledTime?, tip?}` | `{status, bookingId, booking, message}` |
| GET | `/api/bookings/pending` | driver | `List<BookingDto>`: open requests, minus ones this driver declined |
| GET | `/api/bookings/user/{userId}` | commuter, self | `List<BookingDto>` newest first |
| GET | `/api/bookings/driver/{driverId}` | driver, self | `List<BookingDto>` newest first |
| GET | `/api/bookings/driver/{driverId}/earnings` | driver, self | `{totalEarnings, tripCount, availableBalance, history}` |
| PUT | `/api/bookings/{bookingId}/offer?fare=…` | driver (from token), online | `BookingDto` with status OFFERED and `offerExpiresAt`. Nothing is charged. 400 `INVALID_AMOUNT` (fare ≤ 0, above the max, more than 2 decimals); 409 `BOOKING_NOT_PENDING` (someone else's offer is open); 409 `DRIVER_OFFLINE` |
| PUT | `/api/bookings/{bookingId}/accept-offer` | the booking's commuter | `BookingDto` (ACCEPTED, with phone numbers). The wallet is charged fare + tip now. 409 `INSUFFICIENT_FUNDS` with `required` and `balance` (the offer stays open, so top up and retry); 409 `OFFER_NOT_AVAILABLE` (expired or withdrawn) |
| PUT | `/api/bookings/{bookingId}/decline-offer` | the booking's commuter | `BookingDto` back to PENDING for other drivers |
| POST | `/api/bookings/{bookingId}/decline/{driverId}` | driver, self | `{status:"DECLINED"}` |
| PUT | `/api/bookings/{bookingId}/complete/{driverId}` | assigned driver | `{status, booking, earned}`; 409 `ALREADY_COMPLETED` |
| PUT | `/api/bookings/{bookingId}/cancel` | the booking's commuter or assigned driver | `BookingDto`. Cancelling an ACCEPTED trip refunds fare + tip; a driver cancelling their open offer just withdraws it (back to PENDING); 409 if already cancelled or completed |

**Lifecycle:** PENDING → (driver offers) OFFERED → (commuter accepts) ACCEPTED
→ COMPLETED. An offer lasts `tekisi.booking.offer-ttl-seconds` (default 120);
if the commuter declines, the driver withdraws, or it lapses, the booking goes
back to PENDING.

`BookingDto` carries `driverName`, `taxiPlate` and `commuterName` from OFFERED
onwards, and `driverPhone` / `commuterPhone` once ACCEPTED (null before that).

**Money:** the commuter's wallet is charged fare + tip only when they accept
an offer. The driver is credited on complete. Every movement is a row in
`commuter_schema.wallet_transactions`.

**Idempotency:** `POST /create`, `PUT /offer`, `PUT /accept-offer`, `PUT /complete` and payout
requests honour `Idempotency-Key`, scoped per user. Use one UUID per logical
intent and retry safely. A retry while the first is still running gets 409
`IDEMPOTENCY_IN_PROGRESS`.

**Correlation-ID:** every endpoint accepts `X-Correlation-Id`; if
omitted, the backend generates one and echoes it back. Always log the
echoed value on the frontend so support can grep audit rows.

### 3.3 Wallet & payouts

| Method | Path | Auth | Returns |
|---|---|---|---|
| GET | `/api/wallet/{userId}` | commuter, self | `{balance, totalTrips}` |
| POST | `/api/wallet/{userId}/topup?amount=…` | commuter, self | `{balance, totalTrips}`. R10–R2000. 403 `TOPUP_DISABLED` when no payment provider is configured (always the case in prod until a real gateway is added; the sandbox provider is dev only) |
| GET | `/api/payouts/driver/{driverId}` | driver, self | `List<PayoutDto>` (account number and phone masked) |
| POST | `/api/payouts/driver/{driverId}/request` | driver, self; body `{amount, method, bankName?, accountNumber?, accountHolder?, phoneNumber?}` | `PayoutDto` (PENDING; the amount leaves the balance now); 409 `INSUFFICIENT_FUNDS` with `available` |
| GET | `/api/payouts?status=PENDING\|APPROVED\|PAID\|REJECTED` | operator / admin | `List<PayoutOpsDto>` with full bank details (omit `status` for all) |
| PUT | `/api/payouts/{id}/approve` | operator / admin | PENDING → APPROVED |
| PUT | `/api/payouts/{id}/paid` | operator / admin; body `{reference}` | APPROVED → PAID |
| PUT | `/api/payouts/{id}/reject` | operator / admin; body `{reason}` | PENDING/APPROVED → REJECTED; the amount goes back to the driver's balance. 409 `PAYOUT_WRONG_STATUS` for any other move |

Bank account numbers, account holders and payout phone numbers are encrypted
at rest (AES-GCM, key `DATA_ENCRYPTION_KEY`). Drivers only ever see them
masked; operators see them in full.

### 3.4 Notifications, chat, queue, locations

| Method | Path | Auth | Returns |
|---|---|---|---|
| GET | `/api/notifications` | any | the caller's unread notifications |
| GET | `/api/notifications/unread-count` | any | `{count}` |
| PUT | `/api/notifications/read/{id}`, `/api/notifications/read-all` | owner | — |
| DELETE | `/api/notifications/{id}` | owner | — |
| POST | `/api/messages/send` | booking participant; body `{bookingId, content}` | `MessageDto` (`senderId` and `senderRole` come from the token) |
| GET | `/api/messages/{bookingId}` | booking participant | `List<MessageDto>` |
| POST | `/api/queue/join` | driver; body `{taxiRank}` | `QueueTicket` (the existing live ticket if there is one) |
| GET | `/api/queue/status/{rank}` | driver | the SERVING ticket first, then WAITING in order |
| PUT | `/api/queue/{skip\|serve\|complete}/{ticketId}` | ticket owner | `QueueTicket`; `serve` only for the front of the queue |
| POST | `/api/locations/update` | driver; body `{lat, lng}` | — |
| GET | `/api/locations/nearby?lat=&lng=&radius=` | any | online drivers within `radius` m of the caller (default 5000, max 10000), as `{lat, lng}` rounded to ~110 m, no driver ids |
| POST | `/api/agents/safety/panic?bookingId=…` | booking participant or staff | `SafetyAlert` |

### 3.4a Sign-up, verification and passwords

Sign-up bodies (`/api/auth/register/commuter|driver`) also need
`acceptTerms: true` and `termsVersion` (from `GET /api/legal`); passwords are
10+ characters and not a common password (`400 WEAK_PASSWORD`). The response
has `verification: {email: SENT|FAILED, phone: SENT|FAILED|UNAVAILABLE}`.
Until the email **or** phone is verified, booking, top-up, going online,
offers and queues return `403 ACCOUNT_NOT_VERIFIED`.

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/legal` | public | `{version, documents, phoneVerificationAvailable}` |
| POST | `/api/auth/verify-email` body `{token}` | public | token from the emailed `/verify-email?token=` link |
| POST | `/api/auth/password/forgot` body `{email, role, channel: EMAIL\|SMS}` | public | always `202`, whether or not the account exists |
| POST | `/api/auth/password/reset` body `{token, newPassword}` or `{email, role, code, newPassword}` | public | signs out every session of the account |
| GET | `/api/me/status` | commuter/driver | `{verified, emailVerified, phoneVerified, phoneVerificationAvailable, termsAccepted, currentTermsVersion}` |
| POST | `/api/me/verification/email` | commuter/driver | resend the link (max 1/min, 5/h → `429`) |
| POST | `/api/me/verification/phone`, `/api/me/verification/phone/confirm` body `{code}` | commuter/driver | SMS code, 10 min, 5 tries |
| POST | `/api/me/password` body `{currentPassword, newPassword}` | commuter/driver | signs out every session |
| POST | `/api/me/terms` body `{version}` | commuter/driver | accept a new version of the terms |

### 3.4b Your account (POPIA)

| Method | Path | Auth | Returns |
|---|---|---|---|
| GET | `/api/me/export` | commuter or driver | everything held about the caller as one JSON document (profile, trips, ledger, messages they sent, notifications; plus wallet and saved places for commuters, payouts with full bank details, last location and queue tickets for drivers). Sent with `Content-Disposition: attachment` |
| POST | `/api/me/delete` | commuter or driver; body `{password}` | `{status: "DELETED"}`. Anonymises the account (name, email, phone, plate, licence), redacts chat they wrote, deletes notifications, saved places, queue tickets and location, and cancels PENDING requests. Trips and ledger rows are kept, de-identified. The caller's tokens stop working at once |

Deletion is refused with `403 PASSWORD_INCORRECT`, or `409` with code
`OPEN_TRIP` (an OFFERED/ACCEPTED trip), `WALLET_NOT_EMPTY` (commuter),
`BALANCE_NOT_EMPTY` or `PAYOUT_IN_PROGRESS` (driver). Staff accounts can't
use it.

### 3.5 Operator / agent surface

Locked to `ROLE_OPERATOR` or `ROLE_ADMIN` in `SecurityConfig` (except the
panic button above). Create the first operator with
`ADMIN_BOOTSTRAP_EMAIL` / `ADMIN_BOOTSTRAP_PASSWORD` and log in with
`role: "OPERATOR"`.

| Method | Path | Returns |
|---|---|---|
| GET | `/api/insights?severity=&code=&bookingId=&since=&acknowledged=&page=&size=` | `{items, page, size, totalElements, totalPages}`; each item has `acknowledgedAt`, `acknowledgedBy`, `acknowledgedNote`. `acknowledged=false` lists only alerts nobody has picked up |
| PUT | `/api/insights/{id}/ack` body `{note?}` | "I'm handling this": records the operator and time; `409 ALREADY_ACKNOWLEDGED` if someone already did |
| GET | `/api/ops/drivers?status=PENDING_APPROVAL\|APPROVED\|REJECTED` | drivers to review, oldest sign-ups first |
| PUT | `/api/ops/drivers/{id}/approve` | approve a driver (they're notified) |
| PUT | `/api/ops/drivers/{id}/reject` body `{reason}` | reject or suspend; also takes them offline; the driver sees the reason |
| POST | `/api/agents/dispatch` | `DispatchDecision` |
| POST | `/api/agents/dispatch/auto` | `{decision, autonomous, recommendationStatus, …}`. When it acts, it *offers* the trip to the recommended driver by notification; it never accepts on their behalf |
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
  "code": "VALIDATION_FAILED",
  "fieldErrors": {
    "email":      "must be a well-formed email address",
    "phoneNumber":"phoneNumber must be 8-20 digits/+/-/space/parentheses"
  }
}
```

Frontend should:

- Read `status` and `detail` for the top-level toast.
- Switch on `code` (e.g. `INSUFFICIENT_FUNDS`, `BOOKING_NOT_PENDING`,
  `EMAIL_TAKEN`), never on the message text.
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
- [ ] Token expiry handled — the frontend signs the user out when
      `expiresInSeconds` passes and on any 401 (no refresh-token flow yet).

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

## 7. History

Sections 1.1–1.3 describe the first frontend-readiness pass. The
security / money / Flyway pass that followed (see `git log`) changed the
contract in section 3: permit-all mode and
`PUT /api/auth/driver/{id}/complete-trip` were removed, notifications no
longer take ids in the path, `cancel` and the wallet no longer take
`?role=`, and `/api/bookings/pending/{driverId}` became
`/api/bookings/pending`.

---

## 8. When to come back to this document

- **First-week wireup**: read sections 1–3.
- **When generating the typed client**: section 2 step 4 + section 3.
- **When debugging a 4xx**: section 4 (error envelope shape).
- **When promoting from staging to prod**: section 1.4 (auth posture)
  and `docs/DEPLOY.md` (deploy procedure).
- **When deciding what feature to wire next**: section 6 (what's intentionally
  not exposed yet).
