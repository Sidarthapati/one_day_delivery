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
- **What the demo did:** set `routing.solver.time-limit-seconds: 8` (in the **app** yaml) to keep replans responsive — but a single replan still takes ~8 **minutes** in the worst case because `recommendVanCount()` runs a fresh `solver.solve()` for each candidate van count `k = lowerBound..vertexCount` (a **linear scan**), and every *infeasible* `k` burns the **full** 8s solver budget before failing. ~60 infeasible attempts × 8s ≈ 8 min.
- **Root cause (deepened 2026-06-13):** when per-vertex demand exceeds `capacity_packets`, the model is **structurally infeasible at every `k`** (no number of vans helps — a single vertex's deliver/collect can't fit one van), yet the scan still tries each `k` to its time limit. The `recommended 62` figure the UI shows is the `vertexCount` fallback (the loop never found a feasible `k`, so it returns the upper bound), **not** a real recommendation — it reads as "no feasible plan" dressed up as a number.
- **Why it's a real concern (not just demo):** nightly batch can absorb minutes, but the latency is dominated by repeated full solves on structurally-infeasible `k`. Real fixes for `f-m6-design`:
  1. **Early structural-infeasibility bail** — before scanning, if any node's `deliverQty` or `collectQty` > `capacity_packets`, fail immediately with a clear message ("vertex X needs 240 packets > 180 capacity; raise capacity or split the territory"). No solve needed.
  2. **Binary search** the feasible `k` instead of the linear scan (feasibility is monotonic in `k`).
  3. **Shorter per-attempt budget** for the scan solves (they only need feasibility, not optimality).
- **Note:** the 8s value itself is demo tuning (DEMO-ONLY); the *inefficiency* it exposed is real. **Not yet fixed — flagged for a design decision.**
- [ ] decision recorded on f-m6-design
- [ ] `recommendVanCount`: early structural-infeasibility bail + binary search

### 6. M3 `ProposalServiceImpl.toResponse` N+1 over assignments (CARRY OVER — real perf bug)
- **File:** `grid/src/main/java/com/oneday/grid/service/impl/ProposalServiceImpl.java` (`toResponse`)
- **Symptom:** `GET /api/proposals/{id}` and every territory-generation `replan` (which ends by returning the proposal) ran **~60s** against the cloud DB for a 200-DA proposal. On local Postgres it was milliseconds, so the bug was invisible until the demo moved to a remote (Oregon) DB.
- **Root cause:** `toResponse` called `assignmentRepository.findByProposalId(...)` **inside** a `.map()` over the regions — one query per DA (200 DAs → 200 queries), each pulling all ~3,466 assignment rows. Local DB hid it (sub-ms round-trips); over a ~0.30s WAN RTT it became 200 sequential round-trips ≈ 60s. Classic N+1 amplified by latency.
- **Fix applied:** hoist the assignment query **out** of the loop — fetch once, filter out `SUPERSEDED`, group hexIds by `daId` into a `Map<UUID, List<UUID>>`, then look each region's DA up in the map. `toResponse` now issues exactly **2** queries (regions + assignments) regardless of DA count.
  ```java
  Map<UUID, List<UUID>> hexIdsByDa = assignmentRepository.findByProposalId(proposal.getId()).stream()
          .filter(a -> a.getStatus() != AssignmentStatus.SUPERSEDED)
          .collect(Collectors.groupingBy(DaHexAssignment::getDaId,
                  Collectors.mapping(DaHexAssignment::getHexId, Collectors.toList())));
  ```
- **Result:** 200-DA proposal **59.75s → 2.84s cold / 1.16s warm** (~20–50×). The residual is the two queries fetching ~3.5k rows over the WAN + serialization (irreducible).
- **Why it's CARRY OVER (not demo-only):** this is a genuine M3 query bug that scales linearly with DA count on *any* non-local DB (i.e. production). It must land on the real grid line.
- **Follow-up:** the **same anti-pattern** affects `getProposals` (the list endpoint runs `toResponse` per proposal → N proposals × 2 queries). Apply the same hoist (or a batch fetch keyed by proposalId) there.
- [ ] ported to `f-m6-design` / M3
- [ ] `getProposals` list endpoint de-N+1'd

---

### 4. Solver consolidated onto too few vans / relaxed-cycle mega-loops
- **Symptom:** with ample capacity the OR-Tools solve put the whole city on 1–2 vans doing ~640-min single loops (it minimises total travel; nothing rewards using more vans, and the shipping solve relaxes the cycle bound to the full operating day).
- **Fix applied (OrToolsVanRouteSolver):** `timeDimension.setGlobalSpanCostCoefficient(100)` — penalises the longest route so the solver balances stops across the available vans (minimise makespan). Result on a 25-DA Delhi case: 2 vans/640-min/1-loop → **6 vans/114-min/6-loops**. Solver tests still green (they assert all-nodes-visited + capacity/span bounds, not van count).
- **Still true (explain to stakeholders, not a bug):** van count is **capped by the number of distinct meeting vertices** (set-cover minimises them — e.g. 4 vertices cover 10 DAs → ≤3–4 vans). You cannot deploy more vans than there are useful routes; the 70% utilisation cost floor (CLAUDE.md) means extra vans stay spare. To use more vans: raise demand, use realistic van capacity (not 1000), add DAs, or lower `maxDaToVertexMinutes` (→ more meeting vertices).
- **Open:** decide the real objective weighting (balance vs. min-vans vs. cost floor) on f-m6-design; `recommendVanCount` (min vans for the cycle target) can now read lower than `vansUsed` (balanced) — reconcile the two figures.
- [ ] objective weighting decided on f-m6-design

### 5. Travel-time realism — congestion factor + hub turnaround + dwell (CARRY OVER)
- **Symptom:** plans showed ~600 km/van/day (≈64 km/h pure driving inside Delhi — impossible) because OSRM `/table` is free-flow and hub load/reload between loops wasn't charged.
- **Fixes applied:**
  - `routing.congestion-factor` (default 1.0; demo 1.6) — `TravelMatrixService` multiplies every OSRM duration by it (Q5 calibration). New test `congestionFactorScalesTravelTimes`.
  - `routing.hub-turnaround-minutes` (default 0; demo 15) — set as the hub node's `serviceTimeSeconds` (`RoutingNode.hub(…, turnaroundSeconds)`), so each loop return is charged → counts against the cycle and `n_loops`.
  - `routing.dwell-minutes` 5 → 10 (per-stop handoff); `city_fleet_config.dwell_minutes` seed 5 → 10 to keep the assembler's ETA dwell in sync with the solver's.
- **Result (25 DAs, cap 120, 13 vans):** 600 km/day → ~190–320 km/van; `n_loops` 6 → 4; `cycle` 114 → 160 min; `recommended` now == `vans_used`.
- **Note:** solver uses `properties.dwellMinutes`, assembler uses `fleet.dwellMinutes` — two sources kept equal here; on f-m6-design pick one (the fleet row).
- [ ] ported to f-m6-design + congestion factor calibrated per city

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
| D7 | Route-viz: hide-grid→faded-territory-overlay, fitBounds, 🏢/✈ divIcon markers, **one marker per vertex** numbered 1..n (loops revisit same vertices → markers were stacking with global indices like "20,21"), per-vertex tooltip lists all loop visit-times, **loop selector**, per-loop vs whole-day distance | `demo-ui/src/components/HexMap.jsx`, `RoutesPanel.jsx`, `App.jsx buildRoutes` | Pure UI. `buildRoutes` road-snaps ONE representative loop (all loops identical geometry) and reports `perLoopDistanceKm`/`totalDistanceKm = perLoop×loops`. |
| D8 | ProposalPanel made display-only (removed its own Approve button — App's "Generate Plan" already approves+activates; re-approve → 401) | `demo-ui/src/components/ProposalPanel.jsx` | Fixed the user-reported 401. |
| D9 | CORS `/routing/**` mapping; CORS is `app/WebConfig.java` (allowedOriginPatterns `*` for /api,/internal,/routing) | `app/src/main/java/com/oneday/app/WebConfig.java` | Demo UI talks to backend via Vite proxy; CORS is belt-and-suspenders for direct calls. Prod-safe (just origin patterns). |
| D10 | **Cloud-DB (Render Oregon) tuning** — Hibernate JDBC batching (`jdbc.batch_size: 2000` + `order_inserts`/`order_updates`) in **app** yaml; `reWriteBatchedInserts` + `socketTimeout` in the JDBC URL; Hikari warm pool (`minimum-idle`/`keepalive-time`/`max-lifetime`/`idle-timeout=0`) in `.env`; Hikari `validation-timeout: 3000` self-heal in yaml | `app/src/main/resources/application.yml`, `.env` | Demo runs the **local app against a remote DB** (~0.30s RTT). Without batching, seeding ~3,466 rows = one INSERT round-trip per row (minutes); with it, a handful of multi-row statements (~2–4s). Warm pool avoids the ~1.65s new-connection cost per call; self-heal recovers a pool wedged by laptop-sleep/network-drop. **Gotcha:** `hibernate.jdbc.batch_size` + `socketTimeout` can NOT be set via env vars/data-source-properties (Map keys get `_`→`.` lowercased + the pool is sealed) — they must live in yaml / the JDBC URL respectively. Pure local-dev infra, not a code change. |
| D11 | **Decoupled, reproducible demand seeding** — `/api/demo/seed` takes optional `seed` (deterministic `new Random(seed)`) + `minMinutes`/`maxMinutes` and returns `seed_used`; new `GET /api/demo/demand-count`; UI `SeedControls` (min/max/seed + 🎲) and a **3-step flow** (Seed → Territories → Routes). Territory gen no longer reseeds — it checks `demand-count` and **hard-errors** ("seed demand first") if 0, so it **reuses** the existing snapshot | `grid/.../api/DemoController.java`, `grid/.../repository/HexDemandSnapshotRepository.java`, `demo-ui/src/components/SeedControls.jsx`, `demo-ui/src/api/gridApi.js`, `demo-ui/src/App.jsx` | Makes a plan reproducible (same `seed` → same demand) and stops every territory regen from re-rolling the surface. Demo-only (M4 will produce real demand). |

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
- **2026-06-13** — Solver van-balancing (item #4, CARRY OVER). Route-viz overhaul + ProposalPanel display-only + `/routing/**` CORS (D7/D8/D9, demo-only). Demo confirmed working end-to-end incl. legible per-van loops with numbered stops + loop selector. Still uncommitted on `demo/m6-planning`.
- **2026-06-13/14** — Demo moved from local Postgres to the **Render Oregon** cloud DB; this surfaced several latency bugs. **Item #6 (CARRY OVER):** fixed the N+1 in `ProposalServiceImpl.toResponse` (territory-gen `GET /api/proposals/{id}` 60s → ~1.2s warm). **Item #2 (flagged):** deepened the `recommendVanCount` diagnosis — linear scan × full-budget infeasible solves = ~8 min; the `recommended 62` value is the `vertexCount` fallback (= "no feasible plan"), and the demand is structurally infeasible vs `capacity_packets`. **Demo-only (D10/D11):** cloud-DB batching + Hikari warm-pool/self-heal tuning; decoupled reproducible demand seeding (seed param + demand-count guard + 3-step UI). All still uncommitted on `demo/m6-planning`.
