// Smoke test — 1 VU, a few iterations. Proves the endpoints respond before a real load run.
// Read-only (quote + serviceability); safe against staging.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, CITIES, quoteBody, JSON_HEADERS } from './lib/config.js';

export const options = {
  vus: 1,
  iterations: 5,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<3000'],
  },
};

export default function () {
  const q = http.post(`${BASE_URL}/api/v1/pricing/quote`, quoteBody(), JSON_HEADERS);
  check(q, { 'quote 200': (r) => r.status === 200 });

  const s = http.get(
    `${BASE_URL}/api/grid/serviceable-at?lat=${CITIES.DEL.lat}&lon=${CITIES.DEL.lon}`,
  );
  check(s, { 'serviceable 200': (r) => r.status === 200 });

  const h = http.get(`${BASE_URL}/actuator/health`);
  check(h, { 'health up': (r) => r.status === 200 });

  sleep(1);
}
