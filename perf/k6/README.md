# k6 load & burst tests

Load / spike tests for the Godspeed backend. All scripts are env-driven and default to staging.
The default run is **read-only** (quote + serviceability + health) so it's safe against the shared
staging env; the booking write path is opt-in and should only target a throwaway environment.

## Install

```bash
brew install k6      # macOS
```

## Run

```bash
# 1. Smoke — prove endpoints respond (1 VU, 5 iterations)
BASE_URL=https://one-day-delivery.onrender.com k6 run perf/k6/smoke.js

# 2. Steady load — sustained arrival rate, sizes threads/pool from p95/p99
RATE=40 DURATION=5m k6 run perf/k6/load.js

# 3. Burst / spike — find the breaking point; confirm graceful shedding (429, not 5xx)
PEAK=200 k6 run perf/k6/burst.js
```

## What each proves

| Script | Scenario | Pass criteria |
|--------|----------|---------------|
| `smoke.js` | 1 VU | `http_req_failed < 1%`, p95 < 3s |
| `load.js` | ramping-arrival-rate, hold at `RATE` rps | p95 < 2s, p99 < 4s, fail < 2% |
| `burst.js` | spike to `PEAK` rps | no 5xx; 200-or-429 only; login limiter engages |

## Turning results into the capacity plan

After a `load.js` run, feed p95/p99 and the sustained throughput into
[`../../docs/prod-readiness/CAPACITY-PLAN.md`](../../docs/prod-readiness/CAPACITY-PLAN.md) to set the
Hikari pool size (`DB_POOL_MAX`) and Tomcat thread bounds. The capacity doc explains the derivation.
