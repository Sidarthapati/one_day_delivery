# M6 Demo → Code Carryover Ledger

| Field | Value |
|-------|-------|
| **Demo branch** | `demo/m6-planning` (off `f-m6-design`) |
| **Target branch** | `f-m6-design` (the real M6 line) |
| **Purpose** | Track every change made on the demo branch and classify it: **CARRY OVER** (a genuine pipeline/correctness fix that must land on `f-m6-design`) vs **DEMO-ONLY** (seed/UI/glue that stays here). The demo's scope rule is "demo glue only", but the integration smoke test surfaced real pipeline bugs — those must not get stranded on the demo branch. |

> **How to use:** when the demo work is done, port every **CARRY OVER** row to `f-m6-design` (cherry-pick or re-apply), add a test where noted, and tick the box. Leave **DEMO-ONLY** rows here.

---

## 🔴 CARRY OVER — real pipeline fixes (must land on `f-m6-design`)

### 1. `RoutePlan.id` — remove `@UuidGenerator` (critical; this was THE plan-time blocker)
- **File:** `routing/src/main/java/com/oneday/routing/domain/RoutePlan.java`
- **Change:** removed `@UuidGenerator` (and its import) from the `@Id UUID id` field; left `@Id` so the id is **application-assigned**.
- **Why:** `RoutePlanningServiceImpl.plan()` (and `RoutePlanLifecycleServiceImpl.override()` and `NightlyRoutePlanJob.applyFallback()`) assign `planId` **up front** so the assembled `route_plan_stop` / `da_cron_schedule` children can reference it before persist. `@UuidGenerator` is an unconditional before-insert generator: it overwrote the assigned id with a fresh UUID, so the parent `route_plan` row got a *different* id than the children referenced → `ERROR: insert or update on table "route_plan_stop" violates foreign key constraint "route_plan_stop_route_plan_id_fkey"`. **Every `POST /routing/plans/{cityId}/replan` failed with HTTP 409.** No unit test caught it because the solver/assembler tests mock the repositories.
- **Verified by:** all 3 create sites set `.id(...)` explicitly (RoutePlanningServiceImpl:222, RoutePlanLifecycleServiceImpl:143, NightlyRoutePlanJob:113), so app-assigned ids are always present.
- **Follow-up on f-m6-design:** add a **persistence integration test** (real DB, not mocked repos) that drives `replan` → asserts `route_plan` + `route_plan_stop` + `da_cron_schedule` rows persist and the FKs resolve. Consider auditing the other M6 entities (`RoutePlanStop`, `DaCronSchedule`, etc.) — they auto-generate their *own* id (fine, leaf rows) but if any future entity is assigned-id + FK-parent, same trap applies.
- [ ] ported to `f-m6-design`
- [ ] integration test added

### 2. (Candidate) Solver default time limit / `recommendVanCount` cost
- **What the demo did:** set `routing.solver.time-limit-seconds: 8` (in the **app** yaml) to keep replans responsive — but a single replan still takes ~40s because `recommendVanCount()` runs a fresh `solver.solve()` for each candidate van count `k = lowerBound..vertexCount`, each up to the time limit.
- **Why it may be a real concern (not just demo):** nightly batch can absorb minutes, but the latency is dominated by repeated full solves on infeasible/near-infeasible `k`. Worth a real fix on `f-m6-design`: bound the `k`-search (e.g. binary search, or early-exit once feasible + small margin), or reuse the constrained solve's structure. **Not yet done — flagged for a design decision.**
- **Note:** the 8s value itself is demo tuning (DEMO-ONLY); the *inefficiency* it exposed is real.
- [ ] decision recorded on f-m6-design
- [ ] (optional) `recommendVanCount` search bounded

---

### 4. Solver consolidated onto too few vans / relaxed-cycle mega-loops
- **Symptom:** with ample capacity the OR-Tools solve put the whole city on 1–2 vans doing ~640-min single loops (it minimises total travel; nothing rewards using more vans, and the shipping solve relaxes the cycle bound to the full operating day).
- **Fix applied (OrToolsVanRouteSolver):** `timeDimension.setGlobalSpanCostCoefficient(100)` — penalises the longest route so the solver balances stops across the available vans (minimise makespan). Result on a 25-DA Delhi case: 2 vans/640-min/1-loop → **6 vans/114-min/6-loops**. Solver tests still green (they assert all-nodes-visited + capacity/span bounds, not van count).
- **Still true (explain to stakeholders, not a bug):** van count is **capped by the number of distinct meeting vertices** (set-cover minimises them — e.g. 4 vertices cover 10 DAs → ≤3–4 vans). You cannot deploy more vans than there are useful routes; the 70% utilisation cost floor (CLAUDE.md) means extra vans stay spare. To use more vans: raise demand, use realistic van capacity (not 1000), add DAs, or lower `maxDaToVertexMinutes` (→ more meeting vertices).
- **Open:** decide the real objective weighting (balance vs. min-vans vs. cost floor) on f-m6-design; `recommendVanCount` (min vans for the cycle target) can now read lower than `vansUsed` (balanced) — reconcile the two figures.
- [ ] objective weighting decided on f-m6-design

## 🟡 NEEDS A DESIGN DECISION — surfaced by the demo, not yet fixed anywhere

### 3. APPROVED → ACTIVE assignment activation (M3↔M6 lifecycle seam)
- **Observation:** M3 nightly `approve` sets `da_hex_assignment.status = APPROVED`. M6's `GridDataAdapter.getDaTerritories` (and the grid map view `getActiveAssignments`) read **`ACTIVE`** only. Today nothing flips nightly-APPROVED → ACTIVE (only intraday override / tile-share approvals set ACTIVE). So M6 nightly planning reads **zero territories** off a freshly-approved M3 grid.
- **Question for the real system:** does M6 nightly planning consume `APPROVED` (the night's plan) or `ACTIVE` (day-of go-live)? If the former, `getDaTerritories` should accept `APPROVED` (or `APPROVED ∪ ACTIVE`). If the latter, there must be a real day-of activation job (scheduler) that M6 runs after.
- **Demo workaround:** `POST /api/demo/activate` flips APPROVED→ACTIVE on demand (see DEMO-ONLY below). **This masks the question — resolve it for real on f-m6-design / M3.**
- [ ] decision recorded (who activates, and what state M6 reads)

---

## 🟢 DEMO-ONLY — stays on `demo/m6-planning` (do NOT carry over)

| # | Change | File(s) | Note |
|---|--------|---------|------|
| D1 | `routing:` config block (osrm base-url → Hetzner via `ROUTING_OSRM_BASEURL`, cycle/window/dwell/cities) added to the **app** yaml | `app/src/main/resources/application.yml` | The module yaml is shadowed on the classpath; this is local-demo wiring. Real envs set `ROUTING_OSRM_BASEURL` per-deploy. The **solver time-limit** lives here too (demo value 8s). |
| D2 | `/routing/**` added to demo security matcher + CSRF-ignore | `app/src/main/java/com/oneday/app/DemoSecurityConfig.java` | `@Profile("!prod")` demo-only chain. M6 endpoints were falling through to the auth chain (`anyRequest().authenticated()`) → 401. Prod security is unchanged. |
| D3 | `DemoController` (`/api/demo/seed`, `/hexes/{id}/demand`, `/hexes/{id}/detail`, **`/activate`**) + `DemoHexDemandRequest` ported into grid | `grid/src/main/java/com/oneday/grid/api/DemoController.java`, `grid/.../dto/request/DemoHexDemandRequest.java` | Both `@Profile("!prod")`. Seeds synthetic `h3_hex_demand_snapshot` because M4 isn't producing demand yet. `/activate` is the demo go-live shim (see item #3). |
| D4 | Demand seeding (synthetic) | via `/api/demo/seed` | M4 absent. Real demand comes from M4's `shipment_leg_events` etc. |
| D5 | UI (`demo-ui/`), `routingApi.js`, fleet/route-viz components | `demo-ui/**` (Phase 3+) | Pure demo front-end. |
| D6 | Fleet bumped per-city via SQL/PUT during calibration | `city_fleet_config` rows | Calibration data, not code. |

---

## 🟢/🔵 Phase-2 backend enablers — BUILT (legitimate M6 API → CARRY OVER, auth TBD)
New M6 endpoints the UI needs; they touch no existing logic and the design lists them. Add `RoutingFleetController` + DTOs (`FleetConfigResponse`, `FleetConfigUpdateRequest`, `LogisticsNodeResponse`, `RouteStopGeoResponse`) and `GridDataAdapter.vertexCoords()`:
- `GET/PUT /routing/fleet/{cityId}` — read/update `city_fleet_config` (the only mutable M6 table). PUT is partial (null fields keep stored value).
- `GET /routing/nodes/{cityId}` — hub/airport coords for map markers.
- `GET /routing/plans/{cityId}/stops` — all stops for the active plan, enriched with vertex lat/lon (resolved via `GridDataAdapter.vertexCoords` → grid `/vertices`), so the UI can draw van polylines without shipping every city vertex.
- All 26 routing tests pass (incl. `RoutingArchitectureTest` — `vertexCoords` stays within the allowed grid imports).

- [ ] decide whether these ship on `f-m6-design` as-is or with prod auth tightened (demo runs permitAll under `!prod`)

---

## Changelog
- **2026-06-09** — Created. Logged item #1 (RoutePlan @UuidGenerator FK bug, fixed), #2 (solver latency, flagged), #3 (APPROVED→ACTIVE seam, workaround), D1–D6 demo-only. Phase 1 integration smoke **PASSED** after item #1's fix.
- **2026-06-09** — Phases 2–4 done. Built the fleet/nodes/enriched-stops endpoints (CARRY OVER). Ported + rewired the React UI (`demo-ui/`) onto this branch: deep snake→camel in the fetch layer, snake_case request bodies, Vite proxy for /api + /routing + /osrm (no CORS), 3 view modes (Demand / DA territories / Van routes), fleet panel, road-snapped van polylines via OSRM, hub/airport markers, ETA tooltips, plan summary. Full UI flow verified through the proxy. Runbook: `M6-DEMO.md`.
