// k6 baseline scenario for Tekisi.
//
// Drives the full booking flow:
//   1) commuter login -> JWT
//   2) POST /api/bookings/create
//   3) driver login -> JWT
//   4) PUT /api/bookings/{id}/offer?fare=...        (driver, must be online)
//   5) PUT /api/bookings/{id}/accept-offer          (commuter; charges the
//      wallet, so top it up first or this returns 409 INSUFFICIENT_FUNDS)
//   6) PUT /api/bookings/{id}/complete/{driverId}
//
// Three load profiles. Pick via env:
//   BASELINE  (1x, default): 5 VUs for 2 min
//   PEAK      (3x):          20 VUs for 5 min
//   SPIKE     (10x):         100 VUs for 5 min
//
// Run:  k6 run -e PROFILE=BASELINE -e BASE_URL=https://staging.tekisi perf/baseline.js
//
// Pass criteria are documented in perf/BASELINE.md.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PROFILE = __ENV.PROFILE || 'BASELINE';

const profiles = {
    BASELINE: { vus: 5,   duration: '2m',  threshold: { p95: 800 } },
    PEAK:     { vus: 20,  duration: '5m',  threshold: { p95: 1500 } },
    SPIKE:    { vus: 100, duration: '5m',  threshold: { p95: 3000 } },
};
const cfg = profiles[PROFILE];

export const options = {
    vus: cfg.vus,
    duration: cfg.duration,
    thresholds: {
        http_req_duration: [`p(95)<${cfg.threshold.p95}`],
        http_req_failed:   ['rate<0.01'],
    },
};

const bookingCreate = new Trend('tekisi_booking_create_ms');
const bookingAccept = new Trend('tekisi_booking_accept_ms');
const bookingComplete = new Trend('tekisi_booking_complete_ms');

function login(email, password) {
    const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({
        email, password, role: 'COMMUTER',
    }), { headers: { 'Content-Type': 'application/json' } });
    check(r, { 'login 200': (r) => r.status === 200 });
    return r.json('token');
}

export default function () {
    // Bring-your-own seeded users for staging.
    const commuterToken = login(__ENV.COMMUTER_EMAIL || 'commuter@example.com',
                                __ENV.COMMUTER_PASSWORD || 'password');
    const driverToken   = login(__ENV.DRIVER_EMAIL || 'driver@example.com',
                                __ENV.DRIVER_PASSWORD || 'password');

    const create = http.post(`${BASE}/api/bookings/create`, JSON.stringify({
        pickupLocation: 'Bree St Rank',
        destination: 'Sandton',
    }), {
        headers: {
            Authorization: `Bearer ${commuterToken}`,
            'Content-Type': 'application/json',
            'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
        },
    });
    bookingCreate.add(create.timings.duration);
    check(create, { 'create 200/201': (r) => r.status >= 200 && r.status < 300 });

    sleep(1);
    const bookingId = create.json('bookingId');
    const offer = http.put(`${BASE}/api/bookings/${bookingId}/offer?fare=80`,
        null, { headers: { Authorization: `Bearer ${driverToken}` } });
    check(offer, { 'offer 200': (r) => r.status === 200 });
    const accept = http.put(`${BASE}/api/bookings/${bookingId}/accept-offer`,
        null, { headers: { Authorization: `Bearer ${commuterToken}` } });
    bookingAccept.add(accept.timings.duration);

    sleep(2);
    const complete = http.put(`${BASE}/api/bookings/${bookingId}/complete/${__ENV.DRIVER_ID || 1}`,
        null, { headers: { Authorization: `Bearer ${driverToken}` } });
    bookingComplete.add(complete.timings.duration);
}
