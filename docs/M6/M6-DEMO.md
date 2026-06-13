# M6 Planning Demo — Runbook

An interactive map to verify the M6 **plan-time** pipeline end-to-end against real M3 territories and
real OSRM: pick a city, see the H3 grid + DA territories, set the fleet, generate van routes, and
watch the road-snapped loops drawn across the city with ETAs.

> Scope: demo glue only (seed + a few endpoints + a React UI). Pipeline fixes made while building
> this are tracked in **`M6-DEMO-CARRYOVER.md`** and must propagate to `f-m6-design`.

---

## Prerequisites
- **JDK 21** (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21`; the enforcer rejects JDK 25).
- **PostgreSQL 16** running locally (`brew services start postgresql@16`) — db `oneday`, user `oneday`, pw `secret`.
- **Node 18+** / npm.
- **OSRM** — the shared Hetzner instance `http://46.225.155.64:5000` (full India, `/table` + `/route`).
  Override with `ROUTING_OSRM_BASEURL` (backend) and `VITE_OSRM` (UI) if it moves. The M6 solver
  **hard-depends** on OSRM `/table` (no geometric fallback), so it must be reachable.

## 1 · Start the backend
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
# default OSRM is the Hetzner box; override only if needed:
# export ROUTING_OSRM_BASEURL=http://<host>:5000
mvn spring-boot:run -pl app          # !prod profile → seeds the 5 city H3 grids + V6 migrations
```
Wait for `Started OneDayDeliveryApplication`. Logs: the Kafka producer will spam
"broker may not be available" if no local Kafka — **harmless** (events are best-effort).

## 2 · Start the UI
```bash
cd demo-ui
npm install            # first time only
npm run dev            # http://localhost:5173 (or 5174 if busy)
```
Vite proxies `/api` + `/routing` → `localhost:8080` and `/osrm` → the Hetzner OSRM, so the browser is
same-origin (no CORS, no backend CORS config needed). Point at a deployed backend with
`VITE_BACKEND=https://… npm run dev`.

## 3 · Drive the demo (in the browser)
The plan date is **tomorrow** (the nightly "plan for tomorrow" semantics); every call uses it.

1. **Pick a city** (top-left). The map recenters; H3 hexes render as a **demand heatmap**.
2. **1 · Generate territories** (sidebar, blue) — set DA count (default 10), click. This:
   seeds synthetic demand → runs the M3 nightly replan → approves it → activates it (APPROVED→ACTIVE).
   The view switches to **DA territories** (hexes colored per DA) and the proposal panel shows
   per-DA load.
3. **2 · Generate routes** (sidebar, green) — set **Vans available** (+ capacity / max-cycle), click.
   This PUTs the fleet config → runs the M6 solver → approves the plan → loads stops + nodes. The
   view switches to **Van routes**:
   - **Colored polylines** per van, road-snapped via OSRM (straight-line fallback).
   - **□ hub** and **✈ airport** markers.
   - **Meeting-vertex dots** — hover for arrival/departure ETA + deliver/collect/load.
   - **Van selector** (sidebar) filters the map to one van; the **plan summary** shows vans-used,
     recommended, provisioning flag, loops/day, realised cycle.
4. **Toggle views** any time with the **Demand / DA territories / Van routes** buttons (top-right).
5. Click any **hex** to inspect / edit its demand (then re-generate).

## Calibration notes (what makes a good-looking plan)
- Demand magnitude matters: the **whole** city grid (e.g. Delhi ~3466 hexes) at high demand funnels
  too much load into each meeting vertex → solver infeasible at any fleet size. The seed defaults
  (`minMinutes=4, maxMinutes=10`) give a feasible, legible plan. Tune in
  `gridApi.seedDemand(...)` or via `POST /api/demo/seed`.
- Delhi reference: **10 DAs + 6 vans → ~2 vans used, 2 loops/day, OK**. More DAs = more (smaller)
  territories = more meeting vertices = lower per-stop load.
- Solver time limit is `routing.solver.time-limit-seconds: 8` (app yaml). A replan still takes
  ~15–40s because `recommendVanCount` runs several solves; a spinner covers it.

## Verify in TablePlus / psql (the plan-time exit criteria)
```sql
-- coherent plan header
SELECT status, vans_used, recommended_van_count, provisioning_flag, n_loops, realised_cycle_minutes
FROM route_plan WHERE valid_for_date = '<tomorrow>' ORDER BY created_at DESC LIMIT 1;
-- stops: ETAs monotonic per (van, loop); peak load ≤ capacity
SELECT van_id, loop_index, count(*), min(planned_arrival), max(planned_departure), max(load_after)
FROM route_plan_stop WHERE route_plan_id = '<planId>' GROUP BY van_id, loop_index ORDER BY 1,2;
-- one cron row per active DA, ≥1 meeting time
SELECT count(*), count(DISTINCT da_id) FROM da_cron_schedule WHERE route_plan_id = '<planId>';
```

## City UUIDs (shared M3 ↔ M6)
| City | cityCode | cityId |
|------|----------|--------|
| Delhi | `delhi` | `f47ac10b-58cc-4372-a567-0e02b2c3d479` |
| Mumbai | `mumbai` | `550e8400-e29b-41d4-a716-446655440000` |
| Bangalore | `bangalore` | `6ba7b810-9dad-11d1-80b4-00c04fd430c8` |
| Hyderabad | `hyderabad` | `6ba7b811-9dad-11d1-80b4-00c04fd430c8` |
| Chennai | `chennai` | `6ba7b812-9dad-11d1-80b4-00c04fd430c8` |

## Troubleshooting
| Symptom | Cause / fix |
|---------|-------------|
| `Generate routes` → "No feasible routes" | Fleet too small for the demand, or demand too high. Lower seed demand or raise vans; check `recommended_van_count` in the alert. |
| M6 replan 409 / FK error on `route_plan_stop` | The `RoutePlan.id @UuidGenerator` bug (fixed here — see carryover #1). Ensure you're on this branch's build. |
| Territories view empty after generate | Assignments didn't activate. Re-run **Generate territories** (it calls `/api/demo/activate`). M6 reads `ACTIVE`, not `APPROVED`. |
| Routes drawn as straight lines | OSRM `/route` unreachable from the UI — check `/osrm` proxy target / `VITE_OSRM`. Plan + ETAs are unaffected (geometry only). |
| `da_ids`/`reviewer_id` ignored, 0 DAs | Backend is global snake_case — request bodies must be snake_case (the UI already does this). |

## What this proves / what's still assumed
- **Proves:** real M3 territories → real OSRM matrix → OR-Tools solve → persisted `route_plan` +
  `route_plan_stop` + `da_cron_schedule` → approve → events, all coherent and visualizable.
- **Still assumed / stubbed:** demand is synthetic (M4 absent); DA roster is generated UUIDs (M1
  absent); first/last-mile split is 50/50 (Q3); APPROVED→ACTIVE is a demo shim (real day-of
  activation TBD — carryover #3); cost floor / flight cutoff / hub-sort ports are no-ops.
- This is the **go/no-go gate for PR #5 (execution)** — gate is **GREEN**.
