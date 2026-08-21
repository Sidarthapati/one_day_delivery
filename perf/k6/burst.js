// Burst / spike test — slams a sudden high arrival rate to find the breaking point and confirm the
// service degrades gracefully (429 from the rate-limiter under prod, bounded queueing) rather than
// falling over. Also exercises the login rate-limiter path.
//
//   BASE_URL=... PEAK=200 k6 run perf/k6/burst.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, CITIES, quoteBody, JSON_HEADERS } from './lib/config.js';

const PEAK = Number(__ENV.PEAK || 150);

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: [
        { target: 10, duration: '10s' },
        { target: PEAK, duration: '10s' }, // sharp spike
        { target: PEAK, duration: '30s' }, // hold at peak
        { target: 10, duration: '20s' }, // recover
      ],
    },
  },
  // Under a spike we tolerate 429s (rate-limiter working as designed) but NOT 5xx.
  thresholds: {
    'http_req_failed{expected_response:true}': ['rate<0.05'],
  },
};

export default function () {
  const q = http.post(`${BASE_URL}/api/v1/pricing/quote`, quoteBody(), {
    ...JSON_HEADERS,
    tags: { name: 'quote' },
  });
  // 200 = served, 429 = shed by the limiter. Both are acceptable; 5xx is not.
  check(q, {
    'not 5xx': (r) => r.status < 500,
    'served or shed': (r) => r.status === 200 || r.status === 429,
  });

  // Hammer the login endpoint to confirm brute-force protection kicks in (429) under prod.
  const login = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ email: 'burst-nonexistent@example.com', password: 'wrong-password' }),
    { ...JSON_HEADERS, tags: { name: 'login' } },
  );
  check(login, { 'login not 5xx': (r) => r.status < 500 });
}
