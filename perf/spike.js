// k6 spike scenario invoked by chaos/03-traffic-spike-10x.sh.
// Drives 10x the baseline rate of POST /api/bookings/create for 5 minutes
// to verify the bounded async pool drops loudly via tekisi.events.dropped
// instead of OOMing.

import http from 'k6/http';
import { sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    vus: 100,
    duration: '5m',
    thresholds: {
        http_req_failed: ['rate<0.05'],
    },
};

export default function () {
    http.post(`${BASE}/api/bookings/create`,
        JSON.stringify({
            commuterId: 1,
            pickupLocation: 'Bree St Rank',
            destination: 'Sandton',
        }), {
            headers: {
                'Content-Type': 'application/json',
                'Idempotency-Key': `k6-spike-${__VU}-${__ITER}`,
                Authorization: `Bearer ${__ENV.JWT}`,
            },
        });
    sleep(0.05);
}
