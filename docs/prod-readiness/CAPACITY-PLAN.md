# Capacity Plan (living)

How we size the connection pool and thread pool from measured load, and the current settings.
Re-run [`perf/k6/load.js`](../../perf/k6/load.js) and update the numbers after any material change.

> **Status:** template + starting settings. The measured columns are filled in after the first k6
> run against staging (Branch 2 deliverable — the run itself needs the staging env up).

## The knobs

| Setting | Env var | Default (`application.yml`) | Where |
|---------|---------|-----------------------------|-------|
| Hikari max pool | `DB_POOL_MAX` | 10 | `spring.datasource.hikari.maximum-pool-size` |
| Hikari min idle | `DB_POOL_MIN_IDLE` | 5 | `spring.datasource.hikari.minimum-idle` |
| Tomcat max threads | `TOMCAT_MAX_THREADS` | 200 | `server.tomcat.threads.max` |
| Tomcat min spare | `TOMCAT_MIN_SPARE` | 10 | `server.tomcat.threads.min-spare` |
| Tomcat accept count | `TOMCAT_ACCEPT_COUNT` | 100 | `server.tomcat.accept-count` |

## Sizing method

1. **DB pool ≤ Postgres `max_connections` headroom.** Render's Postgres plans cap connections
   (e.g. ~97 on the small plan, shared with any admin/psql sessions). With a single app instance,
   `DB_POOL_MAX` must stay well under that cap. Rule of thumb (Hikari guidance):
   `pool = ((core_count * 2) + effective_spindle_count)` — for a small Render instance that lands
   around **8–12**, hence the default 10. Raising it only helps if Postgres has the headroom.
2. **Tomcat threads ≥ concurrent in-flight requests at target rps.** By Little's Law,
   `concurrency ≈ throughput(rps) × avg_latency(s)`. Measure `avg_latency` and sustained rps from
   `load.js`, add ~50% headroom. Threads far above that just burn memory; the real bottleneck is the
   DB pool, so most requests should spend their time holding a *thread* not a *connection* (keep DB
   work short).
3. **The pool is the true concurrency limiter for DB-bound endpoints.** If `TOMCAT_MAX_THREADS` ≫
   `DB_POOL_MAX`, excess threads queue on `getConnection()`. That's fine (bounded wait via
   `connection-timeout`) as long as p99 stays under SLA; if not, either shrink the gap or move the
   endpoint off the hot DB path.

## Measured (fill after k6 run)

| Metric | Target | Measured | Notes |
|--------|--------|----------|-------|
| Sustained throughput (quote) | — | _tbd_ | rps at which p95 < 2s |
| p95 latency @ target | < 2000 ms | _tbd_ | |
| p99 latency @ target | < 4000 ms | _tbd_ | |
| avg latency @ target | — | _tbd_ | drives Little's-Law thread count |
| Breaking point (burst) | — | _tbd_ | rps where 5xx or p99 blows SLA |
| Pool exhaustion? | no | _tbd_ | Hikari `pending` metric during hold |

## Current verdict

Defaults (pool 10 / threads 200) are a conservative single-instance starting point. Revisit once the
k6 run gives real latency; if the app is DB-bound (likely), the lever is pool size vs Postgres plan,
not thread count. Horizontal scale (multiple Render instances) needs the rate-limiter moved to a
shared store (Redis) first — tracked as a Branch 2+ follow-up.
