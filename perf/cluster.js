// k6 load test for the multi-instance setup (docker-compose.cluster.yml,
// perf/CLUSTER.md). Unlike baseline.js it brings its own users:
//
//   setup()    signs up PAIRS commuter/driver pairs through the API, confirms
//              their emails via Mailpit, has the operator accept each driver's
//              documents and approve them, puts drivers online and tops up
//              wallets. Every step goes through the load balancer, so it lands
//              on either instance.
//   default    each virtual user runs whole trips for its pair: request ->
//              offer -> accept (wallet charged) -> a few reads -> complete
//              (driver credited), topping the wallet up now and then.
//   teardown() runs the money reconciliation, which must find every rand
//              accounted for after concurrent trips across both instances.
//
// Run (Docker, from the repo root, with the cluster stack up):
//   docker run --rm -i --network host -v "$PWD/perf:/perf" grafana/k6:0.54.0 run \
//     -e OPS_EMAIL=... -e OPS_PASSWORD=... /perf/cluster.js
//
// Env: BASE_URL (http://localhost:8080), MAILPIT_URL (http://localhost:8025),
//      PAIRS (20), DURATION (2m), OPS_EMAIL / OPS_PASSWORD (bootstrap operator).

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const MAILPIT = __ENV.MAILPIT_URL || 'http://localhost:8025';
const PAIRS = parseInt(__ENV.PAIRS || '20', 10);
const PASSWORD = 'k6-sunrise-kettle-9';
const FARE = 5;
const PDF = '%PDF-1.4\n% k6 load-test document\n%%EOF\n';

export const options = {
    setupTimeout: '10m',
    teardownTimeout: '2m',
    scenarios: {
        trips: { executor: 'constant-vus', vus: PAIRS, duration: __ENV.DURATION || '2m' },
    },
    thresholds: {
        'http_req_duration{phase:trip}': ['p(95)<800'],
        'http_req_failed{phase:trip}': ['rate<0.01'],
        checks: ['rate>0.99'],
    },
};

const tripMs = new Trend('tekisi_trip_ms', true);
const trips = new Counter('tekisi_trips_completed');
const json = { 'Content-Type': 'application/json' };
const auth = (t) => ({ Authorization: `Bearer ${t}` });

function must(res, what, ok = (r) => r.status >= 200 && r.status < 300) {
    if (!ok(res)) fail(`${what}: ${res.status} ${res.body && res.body.substring(0, 300)}`);
    return res;
}

function login(email, role) {
    const r = must(http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: role === 'OPERATOR' ? __ENV.OPS_PASSWORD : PASSWORD, role }), { headers: json }), `login ${email}`);
    return { token: r.json('token'), id: r.json('userId') };
}

function emailToken(to) {
    for (let i = 0; i < 40; i++) {
        const s = http.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`);
        const msgs = s.status === 200 ? s.json('messages') || [] : [];
        for (const m of msgs) {
            const text = http.get(`${MAILPIT}/api/v1/message/${m.ID}`).json('Text') || '';
            const hit = /\/verify-email\?token=([A-Za-z0-9_-]+)/.exec(text);
            if (hit) return hit[1];
        }
        sleep(0.25);
    }
    fail(`no verification email for ${to}`);
}

export function setup() {
    if (!__ENV.OPS_EMAIL || !__ENV.OPS_PASSWORD) fail('Set OPS_EMAIL and OPS_PASSWORD (the bootstrap operator).');
    const run = `${Date.now()}`.slice(-6);
    const version = must(http.get(`${BASE}/api/legal`), 'legal').json('version');
    const ops = login(__ENV.OPS_EMAIL, 'OPERATOR');
    const pairs = [];

    for (let i = 0; i < PAIRS; i++) {
        const c = `k6-c-${run}-${i}@example.test`;
        const d = `k6-d-${run}-${i}@example.test`;
        must(http.post(`${BASE}/api/auth/register/commuter`, JSON.stringify({
            fullName: `Load Commuter ${i}`, email: c, phoneNumber: '0710000123', password: PASSWORD,
            acceptTerms: true, termsVersion: version,
        }), { headers: json }), `register ${c}`);
        must(http.post(`${BASE}/api/auth/register/driver`, JSON.stringify({
            fullName: `Load Driver ${i}`, email: d, phoneNumber: '0820000123', password: PASSWORD,
            taxiPlate: `K${run}${String(i).padStart(2, '0')}`, licenseNumber: `K6-${i}`,
            acceptTerms: true, termsVersion: version,
        }), { headers: json }), `register ${d}`);
        pairs.push({ commuterEmail: c, driverEmail: d });
    }
    for (const p of pairs) {
        for (const email of [p.commuterEmail, p.driverEmail]) {
            must(http.post(`${BASE}/api/auth/verify-email`, JSON.stringify({ token: emailToken(email) }), { headers: json }), `verify ${email}`);
        }
    }

    const nextYear = `${new Date().getFullYear() + 1}-12-31`;
    for (const p of pairs) {
        p.driver = login(p.driverEmail, 'DRIVER');
        for (const type of ['DRIVERS_LICENCE', 'PRDP', 'OPERATING_LICENCE']) {
            must(http.post(`${BASE}/api/me/documents`, {
                type, expiresOn: nextYear, file: http.file(PDF, `${type.toLowerCase()}.pdf`, 'application/pdf'),
            }, { headers: auth(p.driver.token) }), `upload ${type}`);
        }
        const docs = must(http.get(`${BASE}/api/ops/drivers/${p.driver.id}/documents`, { headers: auth(ops.token) }), 'docs').json('items');
        for (const doc of docs) {
            must(http.put(`${BASE}/api/ops/documents/${doc.id}/accept`, '{}', { headers: Object.assign({}, json, auth(ops.token)) }), 'accept doc');
        }
        must(http.put(`${BASE}/api/ops/drivers/${p.driver.id}/approve`, null, { headers: auth(ops.token) }), 'approve driver');
        must(http.put(`${BASE}/api/auth/driver/${p.driver.id}/status?active=true`, null, { headers: auth(p.driver.token) }), 'go online');

        p.commuter = login(p.commuterEmail, 'COMMUTER');
        must(http.post(`${BASE}/api/wallet/${p.commuter.id}/topup?amount=500`, null, { headers: auth(p.commuter.token) }), 'top up');
    }
    return { pairs: pairs.map((p) => ({ c: p.commuter, d: p.driver })), ops: ops.token };
}

export default function (data) {
    const { c, d } = data.pairs[(__VU - 1) % data.pairs.length];
    const tags = { tags: { phase: 'trip' } };
    const start = Date.now();

    // Every 40th trip: top up (well inside the daily limits once past 3 trips).
    if (__ITER % 40 === 39) {
        http.post(`${BASE}/api/wallet/${c.id}/topup?amount=200`, null, Object.assign({ headers: auth(c.token) }, tags));
    }
    const create = http.post(`${BASE}/api/bookings/create`, JSON.stringify({ pickupLocation: `Load rank ${__VU}`, destination: 'Sandton' }),
        Object.assign({ headers: Object.assign({ 'Idempotency-Key': `k6-${__VU}-${__ITER}-${start}` }, json, auth(c.token)) }, tags));
    if (!check(create, { 'create 200': (r) => r.status === 200 })) {
        console.warn(`create failed: ${create.status} ${String(create.body).substring(0, 200)}`);
        return;
    }
    const id = create.json('bookingId');

    const offer = http.put(`${BASE}/api/bookings/${id}/offer?fare=${FARE}`, null, Object.assign({ headers: auth(d.token) }, tags));
    check(offer, { 'offer 200': (r) => r.status === 200 });
    const accept = http.put(`${BASE}/api/bookings/${id}/accept-offer`, null, Object.assign({ headers: auth(c.token) }, tags));
    check(accept, { 'accept 200': (r) => r.status === 200 });

    // What the apps read while a trip is on.
    check(http.get(`${BASE}/api/bookings/user/${c.id}`, Object.assign({ headers: auth(c.token) }, tags)), { 'bookings 200': (r) => r.status === 200 });
    check(http.get(`${BASE}/api/wallet/${c.id}`, Object.assign({ headers: auth(c.token) }, tags)), { 'wallet 200': (r) => r.status === 200 });
    check(http.get(`${BASE}/api/bookings/pending`, Object.assign({ headers: auth(d.token) }, tags)), { 'pending 200': (r) => r.status === 200 });

    const done = http.put(`${BASE}/api/bookings/${id}/complete/${d.id}`, null, Object.assign({ headers: auth(d.token) }, tags));
    if (check(done, { 'complete 200': (r) => r.status === 200 })) {
        trips.add(1);
        tripMs.add(Date.now() - start);
    }
    sleep(0.5);
}

export function teardown(data) {
    const r = http.post(`${BASE}/api/ops/reconciliation/run`, null, { headers: auth(data.ops), timeout: '120s' });
    const run = r.status === 200 ? r.json('run') : null;
    console.log(`reconciliation: ${run ? `${run.status}, ${run.ledgerRows} ledger entries, critical=${run.criticalCount}` : r.status}`);
    check(r, { 'reconciliation OK': () => run !== null && run.status === 'OK' });
}
