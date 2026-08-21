// Steady-state load test — ramps to a sustained arrival rate and holds, to size Tomcat threads +
// Hikari pool from real p95/p99 under continuous read traffic.
//
// Read-only by default (quote + serviceability + health). The booking write path is opt-in
// (INCLUDE_WRITES=true) because it creates real shipments — only enable against a throwaway env.
//
//   BASE_URL=https://one-day-delivery.onrender.com k6 run perf/k6/load.js
//   RATE=40 DURATION=5m k6 run perf/k6/load.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, CITIES, quoteBody, JSON_HEADERS } from './lib/config.js';

const RATE = Number(__ENV.RATE || 30); // requests/sec target
const DURATION = __ENV.DURATION || '3m';
const quoteLatency = new Trend('quote_latency', true);

export const options = {
  scenarios: {
    steady: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { target: RATE, duration: '30s' }, // ramp up
        { target: RATE, duration: DURATION }, // hold
        { target: 0, duration: '15s' }, // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<2000', 'p(99)<4000'],
    quote_latency: ['p(95)<2000'],
  },
};

const PAIRS = [
  [CITIES.DEL, CITIES.BOM],
  [CITIES.BLR, CITIES.HYD],
  [CITIES.DEL, CITIES.MAA],
  [CITIES.BOM, CITIES.BLR],
];

export default function () {
  const [o, d] = PAIRS[Math.floor(Math.random() * PAIRS.length)];

  const q = http.post(`${BASE_URL}/api/v1/pricing/quote`, quoteBody(o, d), {
    ...JSON_HEADERS,
    tags: { name: 'quote' },
  });
  quoteLatency.add(q.timings.duration);
  check(q, { 'quote ok': (r) => r.status === 200 });

  const s = http.get(`${BASE_URL}/api/grid/serviceable-at?lat=${o.lat}&lon=${o.lon}`, {
    tags: { name: 'serviceable' },
  });
  check(s, { 'serviceable ok': (r) => r.status === 200 });

  sleep(0.5);
}
