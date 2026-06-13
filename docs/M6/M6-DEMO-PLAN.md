# M6 Planning Demo — Implementation Plan

| Field | Value |
|-------|-------|
| **Branch** | `demo/m6-planning` (off `f-m6-design`) |
| **Goal** | An interactive UI to verify the M6 **plan-time** pipeline (PR #1–#4) actually works end-to-end against real M3 data and real OSRM — *before* building the execution layer (PR #5+). |
| **Scope rule** | This branch adds **demo glue only** (seed data, 2 small endpoints, a UI). It does **not** change the M6 planning pipeline. If the demo reveals a pipeline bug, the fix lands on `f-m6-design`, not here. |
| **Status** | Plan approved; implementation not started. |

---

## Why this checkpoint exists

PRs #1–#4 are proven only by unit tests with **mocked OSRM, mocked grid, and a fixed clock**. The real pipeline — real M3 territories → real OSRM `/table` → OR-Tools solve → persisted `route_plan` → approve → events — has never run. PR #5 onward (custody/manifests/execution) is built *on top of* a `route_plan` we are currently only assuming is coherent. A vertical-slice integration check here is cheap insurance: if meeting-point set-cover, ETAs, capacity, or `da_cron_schedule` are wrong, we want to know now, with a map in front of us, not after stacking execution on top.

The user also wants a real operator-facing view: pick a city, see hexes and DA territories on the India map, set the number of vans, generate the plan, and **watch the van routes drawn across the city**.

---

## What already exists (reuse, don't rebuild)

### UI — `demo-ui/` on branch `f-m3-h3-refcator-demo`
A complete **React 18 + Vite + react-leaflet + h3-js + @tanstack/react-query + tailwind** app:
- `src/App.jsx` — toolbar with **city dropdown** (5 cities, recenters map), **view toggle** (Demand heatmap ↔ DA territories), sidebar.
- `src/components/HexMap.jsx` — Leaflet map (OSM tiles), draws every hex from `h3Index` via `cellToBoundary`; demand-heatmap coloring and per-DA territory coloring; click-to-select.
- `src/components/DaControls.jsx` — DA-count input + **Generate Plan**.
- `src/components/ProposalPanel.jsx` — proposal status, **Approve**, per-DA load breakdown.
- `src/components/{Legend,HexPanel}.jsx`, `src/utils/daColors.js` (stable per-DA color hash), `src/api/gridApi.js`.

> ⚠️ That branch commits `node_modules/` and `.vite/`. Port **source only** and `npm install` fresh.

### Backend endpoints already on the current branch
- `GET /api/grid/{cityCode}/tiles?date=` — hexes (`id`, `h3Index`, `active`, `demandScoreMinutes`).
- `GET /api/grid/{cityCode}/vertices` — vertex `id`, `lat`, `lon` (**needed to draw routes** — stops reference vertex ids).
- `GET /api/grid/{cityCode}/assignments?date=` — ACTIVE assignments (`hexId`→`daId`) after approval.
- `POST /api/grid/{cityCode}/replan` `{daIds, date}` → proposal (M3's real solver).
- `POST /api/proposals/{id}/approve` `{reviewerId}` → assignments become ACTIVE.
- M6 (this work): `GET/POST /routing/*` (plans, stops, cron, shuttle, approve, override, replan).

### What's missing for the M6 layer
1. **Van/fleet config control** — needs a `routing` GET/PUT fleet endpoint (none today).
2. **Route generation action** — call M6 `replan` + `approve` from the UI.
3. **Route geometry on the map** — M6 stops carry `hexVertexId` + ETAs, no geometry. UI resolves vertices→lat/lon (`/vertices`) and draws per-van polylines, road-snapped via the Hetzner OSRM `/route`.
4. **Demand seed** — M4 is absent, so `h3_hex_demand_snapshot` must be seeded for the date.
5. **OSRM wiring** — `routing.osrm.base-url` must point at the **Hetzner OSRM** because the M6 *backend* solver hard-depends on OSRM `/table` (M3 has a geometric fallback; M6 does not).

---

## Confirmed inputs
- **OSRM:** Hetzner-hosted, **full India road coverage** (confirmed). Backend (M6 `/table`, M3 adjacency) and UI (`/route` geometry) both point at it. Exact base URL supplied at Phase 1.
- **DA roster:** drive M3's real `replan` with **generated DA UUIDs** (M1 absent; this is what the M3 demo already does). No DA-roster code shim.
- **Demand:** seeded into `h3_hex_demand_snapshot` (M4 absent).

---

## Phases

### Phase 1 — Integration smoke (no UI)
**Goal:** prove the M3→M6 pipeline produces a coherent `route_plan` end-to-end before any UI work.

**Do:**
- Wire OSRM base URL (Hetzner) into `routing.osrm.base-url` and M3's OSRM config (env var / `application.yml`); confirm the backend can reach `/table` and `/route`.
- Boot `mvn spring-boot:run -pl app` (`!prod`, local Postgres 16). `GridSeeder` seeds the 5 H3 grids.
- `demo/seed_city_demand.sql` — parametrised `city_id` + `target_date`; seeds `h3_hex_demand_snapshot` (modest random demand, idempotent `ON CONFLICT(hex_id, snapshot_date)`).
- Curl the real flow: M3 `replan` (explicit DA UUIDs) → `POST /api/proposals/{id}/approve` → M6 `POST /routing/plans/{cityId}/replan?date=` → `POST /routing/plans/{planId}/approve`.

**Exit criteria (verify in TablePlus):**
- `route_plan`: PROPOSED then APPROVED, sane `vans_used` / `recommended_van_count` / `provisioning_flag`.
- `route_plan_stop`: ETAs monotonic within a (van, loop); peak load ≤ capacity.
- `da_cron_schedule`: one row per active DA, ≥1 meeting time, consecutive times ≥30 min apart.
- Events fire on approve (log/console).

> If this fails, **stop** and fix the pipeline on `f-m6-design`. This is the go/no-go gate.

**Risks:** OSRM unreachable from backend → solver can't build the matrix (no fallback in M6); zero/garbage demand → trivial plan.

---

### Phase 2 — Backend demo enablers
**Goal:** the handful of endpoints the UI needs that don't exist yet.

**Do (in `routing`):**
- `GET/PUT /routing/fleet/{cityId}` — read/update `city_fleet_config` (`vans_available`, `capacity_packets`, `cycle_time_min/max_minutes`). Backs the van-config control. Mutable table (already designed mutable).
- `GET /routing/nodes/{cityId}` — hub + airport coords (from `city_logistics_node`) for map markers + shuttle.
- Formalize `demo/seed_city_demand.sql` (+ optional `/api/demo` demand helper only if we want in-UI demand editing; default is SQL-only).
- Unit tests for the fleet endpoint; full `mvn install` green.

**Exit criteria:** every UI data need is served by a real endpoint; build green.

---

### Phase 3 — Port the M3 demo UI onto this branch
**Goal:** re-establish the working M3 slice (map + hexes + territories + generate/approve) as the foundation.

**Do:**
- Copy `demo-ui/` **source only** into `demo/m6-planning`; `npm install`; `npm run dev`.
- Verify against the current backend: city dropdown, hex render, Demand/DA-territory toggle, Generate Plan → Approve.
- Centralize API base URL in one config (env var) so it can target local or a deployed backend.

**Exit criteria:** the M3 territories demo runs unchanged from this branch.

**Risks:** `h3-js` version vs backend H3 resolution mismatch (both must agree on the index); committed `node_modules` on the source branch (copy source only).

---

### Phase 4 — M6 controls + route visualization (the core ask)
**Goal:** set the number of vans, generate routes, and **see the van loops on the map**.

**Do:**
- `src/api/routingApi.js` — fleet GET/PUT, M6 replan/approve, plans, stops, cron, shuttle, nodes.
- **Fleet panel** — set number of vans (+ capacity/cycle); PUT to backend.
- **"Generate Routes"** button — M6 `replan` → `approve`; then load plan + stops.
- **New map view mode "Routes"** (toggle becomes Demand / DA territories / Routes):
  - Per-van **polylines** through ordered stop coords (van-colored via `daColors`-style hash), **road-snapped** via Hetzner OSRM `/route` with a **straight-line fallback**.
  - **Hub** (□) and **airport** (✈) markers; meeting-vertex markers with **ETA** tooltips.
  - **Van + loop selector** (one van/loop, or all).
  - **Plan summary panel** — vans used, recommended vans, `UNDER_PROVISIONED` flag, `n_loops`, realised cycle minutes.

**Exit criteria:** pick a city → set vans → Generate Routes → colored van loops drawn across the real map with ETAs and hub/airport markers.

**Risks:** stop→vertex coord resolution; OSRM `/route` rate/latency for many segments (batch or cache; fall back to straight lines).

---

### Phase 5 — Full-day flow, shuttle, runbook & calibration
**Goal:** a smooth one-click story + documentation + a go/no-go summary.

**Do:**
- **"Plan the day"** single action chaining seed → M3 replan → approve → M6 replan → approve → render, with per-step status.
- **Shuttle timetable** panel (departures/ETAs from `GET /routing/shuttle/{cityId}`).
- `docs/M6/M6-DEMO.md` runbook — prerequisites (Hetzner OSRM URL, Postgres), exact commands, the 5 city UUIDs, verification SQL, troubleshooting.
- **Calibration pass** — tune `maxDaToVertexMinutes`, `cycle`, vans-per-city until routes look realistic; record findings (feeds the real Q4/Q5/Q9 calibration later).

**Exit criteria:** a clean, repeatable demo + a written **"what this proves / what's still assumed"** summary that gates the decision to proceed to PR #5 (execution).

---

## Decisions baked in
- **Road-snapped routes** via Hetzner OSRM `/route` from the UI (with straight-line fallback) — the user wants real routes, not stick lines.
- **Drive M3's real solver** via its endpoints with generated DA UUIDs; only **demand** is seeded (M4 genuinely absent).
- **No changes to the M6 planning pipeline** on this branch — demo glue only.

## Open items to settle during implementation
- Exact Hetzner OSRM base URL + auth (if any) → Phase 1.
- Whether to expose richer fleet knobs (per-city dwell, cadence) in the UI or keep to van count → Phase 4.
- Per-hex demand editing in-UI (port M3's `DemoController`) vs SQL-only seeding → default SQL-only.

---

## Reference appendix

### City UUIDs (shared M3 ↔ M6, from `V6_11` / `grid.cities`)
| City | cityCode | cityId |
|------|----------|--------|
| Delhi | `delhi` | `f47ac10b-58cc-4372-a567-0e02b2c3d479` |
| Mumbai | `mumbai` | `550e8400-e29b-41d4-a716-446655440000` |
| Bangalore | `bangalore` | `6ba7b810-9dad-11d1-80b4-00c04fd430c8` |
| Hyderabad | `hyderabad` | `6ba7b811-9dad-11d1-80b4-00c04fd430c8` |
| Chennai | `chennai` | `6ba7b812-9dad-11d1-80b4-00c04fd430c8` |

### Endpoint inventory
**M3 (grid) — existing:** `GET /api/grid/{cityCode}/tiles`, `/vertices`, `/assignments`; `POST /api/grid/{cityCode}/replan`; `POST /api/proposals/{id}/approve`.
**M6 (routing) — existing (PR #4):** `GET /routing/plans/{cityId}`, `/plans/{cityId}/vans/{vanId}/stops`, `/cron/da/{daId}`, `/cron/da/{daId}/next`, `/shuttle/{cityId}`; `POST /routing/plans/{planId}/approve`, `/plans/{planId}/override`, `/plans/{cityId}/replan`.
**M6 (routing) — to add (Phase 2):** `GET/PUT /routing/fleet/{cityId}`, `GET /routing/nodes/{cityId}`.

### Run commands (target state)
```bash
# Backend (JDK 21; OSRM base url via env)
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ROUTING_OSRM_BASEURL=https://<hetzner-osrm>   # also wire M3's OSRM url
mvn spring-boot:run -pl app                            # !prod → seeds 5 grids

# Seed demand for a city/date
psql -U oneday -d oneday \
  -v city_id="'f47ac10b-58cc-4372-a567-0e02b2c3d479'" \
  -v target_date="'2026-06-10'" \
  -f demo/seed_city_demand.sql

# UI
cd demo-ui && npm install && npm run dev
```
