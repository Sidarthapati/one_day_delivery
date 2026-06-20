# M6 — Van Routing, Scheduling & Custody: Design Doc

| Field | Value |
|-------|-------|
| **Module** | M6 — Van Routing, Scheduling & Custody (`routing`, `com.oneday.routing`) |
| **Status** | Draft v0.3 — full van lifecycle (planning + execution + custody + tracking) |
| **Depends on** | `common` (Kafka contracts, BaseEntity), `grid` / M3 (DA territories, hex vertices, OSRM matrix, demand snapshots) |
| **Consumed by** | M5 (per-DA cron schedule), M7 (van load/unload at the dock), M8 (van scan ledger), M9 (shuttle departure times), M10 (route ETAs, deviation), M4 (custody state transitions), van-driver app |
| **Source PRD** | `docs/PRD-ONE-DAY-DELIVERY.md` §6.3, §8, §9.2 · `docs/MODULES.md` M6 |

> **What M6 is, in one line.** A consolidation van is a **mobile mini-hub** (a moving cross-dock): M6 plans where it goes, schedules when, decides which parcels ride it and to whom, governs every custody handoff, tracks it live, and recovers it when things break. M6 owns *everything van*.

> **How to read this doc.** The doc is in three parts.
> **Part I — Planning (plan-time, nightly batch):** §1–§10. *Where* vans go and *when*.
> **Part II — Execution (run-time, all day):** §11–§15. *Which parcels* ride, *to whom*, *who scans what*, and *what happens when it breaks*. This is the part the v0.1/v0.2 drafts were missing.
> **Part III — Integration & governance:** §16–§21. Boundaries, contracts, constraints, open questions, build order, testing.
> Design decisions are tagged `M6-D-xxx`; constraints `Cn`; open questions `Qn`.

---

## Table of Contents

**Part I — Planning**
1. [What M6 Does](#1-what-m6-does)
2. [Scope & Ownership](#2-scope--ownership)
3. [Industry Context — How This Problem Is Actually Solved](#3-industry-context--how-this-problem-is-actually-solved)
4. [Key Design Decisions](#4-key-design-decisions)
5. [The Routing Model](#5-the-routing-model)
6. [Inputs](#6-inputs)
7. [The Nightly Planning Pipeline](#7-the-nightly-planning-pipeline)
8. [Fleet Sizing](#8-fleet-sizing)
9. [The Hub↔Airport Shuttle](#9-the-hubairport-shuttle)
10. [Nightly Replan, Approval, Override](#10-nightly-replan-approval-override)

**Part II — Execution**
11. [The Van as a Mobile Mini-Hub — Custody & Manifest](#11-the-van-as-a-mobile-mini-hub--custody--manifest)
12. [Parcel ↔ Loop Binding & the Forecast-to-Actual Flow](#12-parcel--loop-binding--the-forecast-to-actual-flow)
13. [The Handoff Protocol & Failure Handling](#13-the-handoff-protocol--failure-handling)
14. [Live Van Tracking & Telemetry](#14-live-van-tracking--telemetry)
15. [Van-Driver App Workflow](#15-van-driver-app-workflow)

**Part III — Integration & Governance**
16. [Cross-Module RACI & Contracts](#16-cross-module-raci--contracts)
17. [Contracts — Events, APIs, Data Model](#17-contracts--events-apis-data-model)
18. [Constraint Catalogue](#18-constraint-catalogue)
19. [Open Questions](#19-open-questions)
20. [Phase Plan](#20-phase-plan)
21. [Testing & Simulation](#21-testing--simulation)

---

# Part I — Planning

## 1. What M6 Does

M6 governs the **consolidation vans** that move parcels between a city's central **hub** and its **delivery associates (DAs)**, who are spread across the H3 hex grid produced by M3. Three realities define the module:

**1. The van is a bidirectional mini-hub.** A single van loop does *both* jobs at once, at the same stops:
- **Deliver (last-mile):** parcels that landed at this city's hub and were sorted for outbound delivery ride *out* on the van and are handed to the DA who will deliver them.
- **Collect (first-mile):** parcels DAs picked up from customers are handed *to* the van and brought back to the hub for sortation → bag → flight.

So every van↔DA meeting is a **simultaneous, parcel-level, two-way custody transfer** — not an abstract exchange of counts.

**2. The loop is periodic.** A van runs a `hub → meeting points → hub` loop with a target **2–3 h cycle time**, repeating through the ~16 h operating day (`n_loops = floor(window / cycle)`). The route *shape* is replanned nightly and locked (NFR-3); the loop *repeats* on a cadence, so a DA meets its van several times a day at the same vertex.

**3. M6 has two halves.** *Plan-time* is a nightly batch (the route shape, the timetable, the fleet recommendation, the shuttle). *Run-time* is an always-on service that, through the day, binds **real parcels** to loops, builds **van manifests**, governs every **custody scan and handoff**, **tracks** the van live, and **recovers** it when a DA no-shows, a van breaks down, or a parcel is mis-sorted. A plan nobody executes and watches is half a system; Part II is the other half.

M6 produces, per city, per day: meeting points covering every DA territory; an ordered, capacity- and cycle-feasible van route per van; the periodic timetable; the per-DA cron schedule (→ M5); a recommended fleet size; the hub↔airport shuttle timetable; and — at run-time — per-loop van manifests, custody events, live positions, deviation signals, and exception/recovery flows.

---

## 2. Scope & Ownership

### 2.1 In scope — M6 owns all of this

| Area | What M6 owns |
|------|--------------|
| **Routing** | Meeting-point selection (set-cover at territory-intersection vertices); the capacitated, time-windowed, pickup-and-delivery VRP; periodisation into loops. |
| **Scheduling** | Per-stop ETAs; per-DA cron schedule; hub↔airport shuttle timetable. |
| **Fleet** | Routing within the configured fleet **and** a recommended fleet size with under-provisioning flagging. |
| **Custody** | The van manifest (which parcels ride which van/loop, to/from whom); the four custody scan points (§11); reconciliation. |
| **Binding** | Mapping *real* parcels to loops, SLA-first then capacity-bounded; runtime overflow / back-pressure. |
| **Handoff** | The van-side handoff protocol at each stop (multi-DA choreography, partial handoff, dwell windows). |
| **Run-time** | Live van tracking, plan-vs-actual ETAs, deviation signalling. |
| **Failures** | DA no-show, van breakdown, mis-sort, lost/damaged-on-van — detection, carry-back, recovery van, escalation. |
| **Driver app contract** | The van-driver app's screens, scans, and API (parallel to M5 owning the DA app). |
| **Config M6 introduces** | Per-city hub & airport coordinates; per-city fleet config. |

### 2.2 Out of scope

- **DA-internal pickup/delivery sequencing** inside a DA's territory — M5's priority queue. M6's responsibility ends at the meeting vertex.
- **Flight assignment / cutoff computation** — M9. M6 consumes a cutoff/cadence.
- **Hub sortation & bagging** — M7. M6 consumes M7's sort output (what's ready to load) and feeds M7 the unloaded collections.
- **The scan ledger itself** — M8 owns the append-only `scan.event` store; M6 *originates* van scans and consumes them (§16).
- **The shipment state machine** — M4 owns it; M6's custody events *drive* transitions but M4 owns the transitions.
- **Building the van-driver mobile app** — a separate client project; M6 defines its contract and serves its API.

### 2.3 Gaps this module closes in the codebase

- **No hub/airport location exists today.** M3 stores hexes, vertices, pincodes — no logistics-node coordinate. M6 introduces `city_logistics_node` (`M6-D-007`).
- **No van custody concept exists today.** The chain DA→van→hub had a hole "on the van." M6 makes the van a first-class custody node (`M6-D-014`).

---

## 3. Industry Context — How This Problem Is Actually Solved

The routing core is a textbook **Vehicle Routing Problem (VRP)** with four standard extensions stacked on:

| Our requirement | Standard name | What it adds |
|-----------------|---------------|--------------|
| Van capacity (max packets) | **CVRP** — Capacitated VRP | Load must never exceed capacity. |
| Each stop both drops *and* collects | **VRPSPD** — Simultaneous Pickup & Delivery | Load fluctuates along the route; the binding limit is the **peak instantaneous load**, not the net. |
| 2–3 h cycle, return-to-hub & flight cutoffs | **VRPTW** — Time Windows | Each stop / the route span has a feasible window. |
| The loop repeats on a cadence | **PVRP** — Periodic VRP | A route is executed on a schedule, not once. |

The **milk-run** term is from lean-manufacturing inbound logistics: one vehicle does a fixed, repeating, consolidated loop touching many suppliers rather than each shipping direct — exactly our model (suppliers = DA meeting points, factory = hub). The custody/manifest layer is **cross-docking**: the van is a moving cross-dock.

**How the industry solves the routing:** Clarke–Wright *savings* (1964) + sweep build a warm start; 2-opt/Or-opt local search refines; **metaheuristics (ALNS, tabu, guided local search)** are what production systems actually run. Commercial routing engines (Routific, OptimoRoute, Onfleet, Wise Systems) wrap these; **UPS ORION** is a proprietary heuristic optimiser; **Amazon** layers learned sequence models on an OR core. The open-source default is **Google OR-Tools `RoutingModel`** (savings/cheapest-arc first solution → guided-local-search metaheuristic; native capacity/time *dimensions* and pickup-and-delivery pairing).

**Our choice (`M6-D-006`):** OR-Tools `RoutingModel` — already a dependency (M3's CP-SAT), models everything we need, scales easily to our size (tens of stops, a handful of vans/city). Clarke–Wright savings is the documented no-solver fallback (mirrors M3's BFS-below-threshold pattern).

> Meeting-point *selection* is **not** the VRP — it is a **set-cover / p-median** problem solved *before* the VRP (§7.2).

---

## 4. Key Design Decisions

### Planning

**M6-D-001 — Meeting points at DA-territory intersection vertices.** The van stops at H3 hex **vertices** (`grid.HexVertex`), preferring vertices on the boundary **shared by several DA territories** so one stop serves multiple DAs. Selection is a weighted set-cover: fewest vertices such that every active DA territory has ≥1 reachable meeting vertex, biased toward high-degree (multi-territory) vertices, bounded by `max_da_to_vertex_minutes`. *Resolves PRD §6.3 / G1 for routing.*

**M6-D-002 — Bidirectional loop; capacity = peak instantaneous load (VRPSPD).** Two quantities per node (`deliver_qty`, `collect_qty`); constrain max load *anywhere* along the route ≤ capacity, not the net.

**M6-D-003 — Route shape fixed nightly; loops repeat on cadence (PVRP, resolves A3).** No per-loop re-solve. Nightly fixes shape + cadence; `n_loops = floor(window / cycle)`; per-loop forecast demand = daily / `n_loops`. Demand freeze at 01:00 IST (as M3).

**M6-D-004 — 70/30 demand, reusing M3's snapshot.** No recompute — aggregate `h3_hex_demand_snapshot.demand_score_orders` + `service_time_min` per territory. One demand truth across M3/M5/M6.

**M6-D-005 — Fleet count is an ops input; M6 also emits a recommended count.** Route within `vans_available`; a second pass computes the minimum vans to cover all territories within cycle+capacity; `vans_available < recommended` ⇒ plan flagged `UNDER_PROVISIONED`.

**M6-D-006 — Solver: OR-Tools `RoutingModel`, Clarke–Wright fallback.** §3.

**M6-D-007 — M6 introduces hub & airport location config.** New `city_logistics_node` (hub + airport lat/lon/city), seeded with M3's `grid.cities`.

**M6-D-008 — Per-DA cron schedule carries the full day's meeting times.** M5 today models one `scheduled_meeting_time`; M6 emits `(vertex, [meeting_times])`. Integration note in §16/§17.

**M6-D-009 — M6 builds its own travel matrix.** M3's `h3_hex_travel_time` is centroid→centroid; M6 routes over *vertices + hub + airport*, so it calls `OsrmClient.getTable` on its own node set (same engine/config, different nodes).

**M6-D-010 — Cost floor stubbed behind `CostFloorPort`.** M2 unbuilt ⇒ flat per-km van cost until it ships.

### Run-time & tracking

**M6-D-011 — Live van tracking belongs in M6, not M10.** The plan-vs-actual / live-ETA loop is a routing-scheduling concern; M10 *consumes* the deviation for SLA. Supersedes the v0.1 scoping. (DA-side equivalent is in M5; the van side is symmetric and is M6's.)

**M6-D-012 — Raw GPS pings stay in-process; only meaningful events hit Kafka.** Telemetry POSTs to an M6 endpoint; the controller calls the tracking service directly (monolith). Kafka is spent only on `VAN_ARRIVED` / `VAN_RUNNING_LATE` / handoff / exception events. Keeps it effectively free (§14.5).

**M6-D-013 — Injectable clock.** All time via an injected `java.time.Clock` so a 16 h day simulates in seconds; lands in P1.

### Custody, manifest & execution (the new core)

**M6-D-014 — The van is a custody node in M8's scan ledger.** Four custody transfer points, each an M8 `scan.event`: **LOAD** (hub→van), **DELIVER** (van→DA), **COLLECT** (DA→van), **UNLOAD** (van→hub). M6 *originates* these scans and owns the van manifest; M8 records the immutable ledger; M4 transitions shipment state. The parcel is never "nowhere": between scans it is *on van X, loop Y*.

**M6-D-015 — Manifests are built from actuals at load time, not nightly.** The nightly plan routes on *forecast*. The **van manifest** (specific parcel IDs → van/loop/stop/DA) is materialised at run-time from M7's sort output (deliver side) and DA accumulation (collect side). Forecast plans capacity; actuals fill it.

**M6-D-016 — Parcel→loop assignment is SLA-first, then capacity-bounded.** A parcel is bound to the **earliest loop that meets its deadline** (delivery SLA for last-mile; flight-cutoff-derived hub-arrival deadline for first-mile), subject to capacity — *not* simply "the next loop with room." Deadline beats convenience.

**M6-D-017 — Runtime overflow → bump then escalate (van-level back-pressure).** If a loop's actual load exceeds capacity, the **latest-deadline** parcels bump to the next feasible loop. If no loop fits before a parcel's deadline ⇒ escalate to station manager + M10 (and optionally recommend an ad-hoc extra van, which needs approval — NFR-3). Never silently drop SLA (mirrors M7's hub-overload principle).

**M6-D-018 — Every handoff is scan-reconciled; partial handoff is legal.** At each stop, expected (manifest) vs actual (scanned) is reconciled per DA. Missing/extra/rejected parcels raise a `HANDOFF_DISCREPANCY` → M11 ticket + M10. A stop can complete partially; unhandled parcels carry to the next loop or are flagged.

**M6-D-019 — Multi-DA rendezvous uses a bounded dwell window; the van never waits past it.** Each stop has a dwell window; DAs assigned to that vertex meet inside it. A DA later than the window gets a partial/zero handoff this loop and catches the next loop — cycle time is hard, the van does not wait indefinitely.

**M6-D-020 — The van-driver app is M6's client; M6 owns the driver-side workflow.** Symmetric to M5 owning the DA app. M6 defines the driver's load/navigate/scan/confirm/return flow and serves its API.

**M6-D-021 — Explicit failure handling for no-show, breakdown, mis-sort, loss.** Each has a defined detection, carry-back/recovery, and escalation path (§13).

---

## 5. The Routing Model

```
                         ┌─────────────────────────────┐
                         │           HUB (depot)        │  load deliveries ↑   unload collections ↓
                         │  every loop starts & ends    │
                         └───────────────┬──────────────┘
                                         │
        loop (target span 2–3h)          ▼
   ●────────●────────●────────●────────●────────● ... ──► hub
   V1       V2       V3       V4       V5       V6
   meeting vertices (H3 hex corners on DA-territory boundaries)

At each meeting vertex Vk (simultaneous, parcel-level):
   van → DA(s):  hand the specific last-mile parcels for those DA(s)      [DELIVER scan]
   DA(s) → van:  hand the first-mile parcels they collected               [COLLECT scan]
   load_after = load_before − delivered + collected   (must stay ≤ capacity at every point)
```

**Nodes** — Depot = hub (`city_logistics_node`, kind=HUB). Meeting vertices = selected `HexVertex` rows, each carrying `(deliver_qty, collect_qty, service_time, served_da_ids[])` for the loop (forecast at plan-time; actual at run-time).

**Edges** — OSRM driving time between node coordinates (`OsrmClient.getTable`, M6's own node set — `M6-D-009`).

**Vehicles** — `vans_available` identical vans, capacity `capacity_packets`, start/end at hub.

**Dimensions (OR-Tools)** — *Capacity* (peak-load constraint, `M6-D-002`); *Time* (route span ≤ `cycle_time_max`).

**Coverage** — every active DA territory served by ≥1 visited vertex (enforced in set-cover, §7.2).

---

## 6. Inputs

| Input | Source | How M6 gets it | Status |
|-------|--------|----------------|--------|
| DA territories (DA → hexes), for the date | M3 `da_hex_assignment` (ACTIVE) | `grid` service/repo (in-process) | ✅ |
| Hex vertices (lat/lon, H3 index) | M3 `h3_hex_vertex` | `HexVertexRepository` | ✅ |
| Per-hex demand (70/30) + service time | M3 `h3_hex_demand_snapshot` | repo | ✅ |
| OSRM driving times | M3 OSRM (`OsrmClient`) | M6 node-set matrix | ✅ reuse |
| Hub & airport coordinates | **new** `city_logistics_node` | M6 owns (`M6-D-007`) | ⚠️ M6 creates |
| Van count + capacity per city | **new** `city_fleet_config` | M6 owns | ⚠️ M6 creates |
| Operating window / shift hours | M3 `grid.shift` (07:00–20:00) | shared config | ✅ |
| Sorted-for-delivery parcels ready to load | M7 sort output | event/query at run-time | ❌ M7 unbuilt — stub |
| First-mile parcels accumulated per DA | M5 / M4 (PICKED_UP) | event at run-time | ❌ partial — stub |
| Parcel delivery SLA / flight cutoff | M4 (SLA), M9 (cutoff) | per-parcel deadline | ❌ M9 unbuilt — stub |
| Per-parcel cost floor | M2 | `CostFloorPort` | ❌ stub |
| Manual override action | M1 + UI | REST (§17.2) | ✅ |
| Live van telemetry (GPS, scans) | Van-driver app | HTTP (§14, §17.2) | run-time |

**Unbuilt-dependency seams** (mirror M3's `DaRosterPort`): `CostFloorPort`, `FlightCutoffPort`, `HubSortPort` (what's ready to load), `DaAccumulationPort` (first-mile parcels per DA). Each has a stub until M2/M7/M9 land, so M6 builds and tests end-to-end now.

---

## 7. The Nightly Planning Pipeline

Five stages, per city, in the 01:00 batch. Stages 1–2 pre-process; stage 3 is the VRP; 4–5 shape output. (All on *forecast*; actuals are bound at run-time, §12.)

**7.1 Stage 1 — Demand aggregation per territory.** For each ACTIVE DA: sum `demand_score_orders` over its hexes → `territory_daily_demand`; split `first_mile_qty` / `last_mile_qty` (symmetric v1 — Q3); per-loop = daily / `n_loops`.

**7.2 Stage 2 — Meeting-point selection (set cover).** Enumerate each territory's candidate vertices (its hexes' `HexVertex` rows), annotated with degree (how many territories touch them). Weighted set-cover: smallest vertex set covering every territory, maximising shared high-degree vertices, minimising DA→vertex distance, bounded by `max_da_to_vertex_minutes` (protects DA's 70% utilisation, NFR-4). Output: `M` = meeting vertices + `vertex → [da_ids]`. Greedy max-coverage (ln n guarantee) is plenty at city scale; CP-SAT exact path available.

**7.3 Stage 3 — Capacitated VRP+TW over `{hub} ∪ M`.** OR-Tools `RoutingModel`: vehicles start/end at hub; capacity dimension with peak-load; time dimension span ≤ `cycle_time_max`; objective minimise travel time (→ cost-floor-weighted when M2 lands). Output: ordered stop list per van for **one loop**.

**7.4 Stage 4 — Periodise.** `n_loops = floor(window / realised_cycle)`; stamp wall-clock ETAs per (van, loop, stop); per vertex, the set of ETAs across loops = the meeting times DAs use.

**7.5 Stage 5 — Derive + persist.** Per DA: `(vertex, [meeting_times])` → `DA_CRON_SCHEDULED` on `oneday.cron.events` (M5). Persist `route_plan` (PROPOSED) + `route_plan_stop` + `da_cron_schedule`. Await approval (§10).

---

## 8. Fleet Sizing

Two solver passes (`M6-D-005`): **(1) Constrained** — route within `vans_available`; this ships. **(2) Recommendation** — re-solve with OR-Tools free to add vehicles at a high fixed cost ⇒ fewest vans covering all territories within cycle+capacity ⇒ `recommended_van_count`. Surface both; if `vans_available < recommended` ⇒ `UNDER_PROVISIONED` + station-manager notification. Capacity is hard in both passes.

---

## 9. The Hub↔Airport Shuttle

Periodic for v1. A single origin–destination leg (hub↔airport) is a *timetable*, not a VRP: configurable cadence (`shuttle_cadence_minutes`), departure times across the window, arrival ETAs from OSRM hub↔airport time. Published as `SHUTTLE_SCHEDULED` on `oneday.cron.events` for M9 (its "cron departure time" input) and M10. When M9 lands, cadence becomes flight-cutoff-driven via `FlightCutoffPort` (Q1). The shuttle carries **sealed flight bags** (from M7), not loose parcels — its custody unit is the bag, simpler than the milk-run.

---

## 10. Nightly Replan, Approval, Override

Mirrors M3's `NightlyReplanJob` (NFR-2/3, PRD §8.3):

| Time (IST) | Action |
|------------|--------|
| **01:00** | `NightlyRoutePlanJob` runs §7 per city → `route_plan` PROPOSED for tomorrow. |
| **06:00** | Escalation: no APPROVED plan for today ⇒ notify station manager. |
| **07:00** | Auto-fallback: still unapproved ⇒ copy yesterday's APPROVED plan forward (as M3). |

- **Approval** — station manager (own city) / admin (any) approves PROPOSED → APPROVED; routes lock.
- **Override** — admin / station manager edits stop order or reassigns a vertex ⇒ new append-only `route_plan` revision, `source=MANUAL_OVERRIDE`, actor + reason; emits `ROUTE_CHANGED` (M5, M10, notify).
- **Append-only** — `route_plan` rows never mutate; revisions supersede (as M3's `assignment_proposal`).

---

# Part II — Execution

## 11. The Van as a Mobile Mini-Hub — Custody & Manifest

This is the heart of the module and what earlier drafts missed. Plan-time moves *counts*; run-time moves *identified parcels* through a chain of custody.

### 11.1 The four custody points (`M6-D-014`)

Each is a physical handoff, an M8 `scan.event`, and an M4 state transition. The parcel is always *somewhere*; between scans it is on a specific van/loop.

```
         (M7 dock)              (meeting vertex)            (meeting vertex)            (M7 dock)
HUB ──LOAD──▶ VAN ──────────▶ VAN ──DELIVER──▶ DA           DA ──COLLECT──▶ VAN ──────▶ VAN ──UNLOAD──▶ HUB
 last-mile parcel                            (last-mile)    (first-mile)                       (first-mile)
```

| # | Transfer | Physical actor(s) | Scan (M8) | M4 state result | M6 records |
|---|----------|-------------------|-----------|-----------------|------------|
| 1 | **LOAD** hub→van | Hub operator (M7) per M6 manifest | `VAN_LOAD` | `AT_DEST_HUB → HANDED_TO_DROP_VAN` | manifest_item PLANNED→LOADED; manifest BUILDING→LOADED |
| 2 | **DELIVER** van→DA | Van driver (M6 app) + DA (M5 app) | `VAN_TO_DA` | `HANDED_TO_DROP_VAN → DROP_COLLECTED` | item LOADED→HANDED_OFF; reconcile |
| 3 | **COLLECT** DA→van | DA (M5 app) + Van driver (M6 app) | `DA_TO_VAN` | `PICKED_UP → HANDED_TO_PICKUP_VAN` | item (collect) →ONBOARD; added to return manifest |
| 4 | **UNLOAD** van→hub | Hub operator (M7) | `VAN_UNLOAD` (= hub in-scan) | `HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB` | return item ONBOARD→RECONCILED; manifest RETURNED→RECONCILED |

> The M4 state names (`HANDED_TO_DROP_VAN`, `DROP_COLLECTED`, `HANDED_TO_PICKUP_VAN`, `AT_ORIGIN_HUB`) already exist in M5's design — M6 aligns to them rather than inventing parallel states. Points 2 & 3 happen **together** at every meeting stop (the simultaneous handoff).

### 11.2 The van manifest

The manifest *is* the van's mini-hub sort plan. Two parts per van per loop per day:

- **Outbound (deliver) manifest** — the specific last-mile parcels loaded for this loop, each tagged `(parcel_id, stop_seq, meeting_vertex, destination_da_id, sla_deadline, status)`.
- **Inbound (collect) manifest** — populated *during* the loop as DAs hand parcels to the van; each `(parcel_id, source_da_id, stop_seq, flight_cutoff_deadline, status)`.

`van_manifest` lifecycle: `BUILDING → LOADED → IN_PROGRESS → RETURNED → RECONCILED`. `van_manifest_item.status`: `PLANNED → LOADED → ONBOARD → HANDED_OFF | RECONCILED | EXCEPTION`.

### 11.3 In-van organisation (`M6-D-014` cont.)

Deliveries are physically loaded **in reverse stop order** (last stop's parcels at the back) so each stop's parcels are reachable first. The driver app shows, at stop k, exactly the parcels for stop k's DAs (filtered by `stop_seq`). This is the cross-dock discipline that makes a many-stop loop workable.

### 11.4 A parcel's journey on the van (concrete)

*Last-mile:* parcel `BLR-...042` lands, M7 sorts it for delivery in hex H (DA-A's territory). M6 binds it to Van 3 / loop 2 (§12). Hub operator scans it onto Van 3 (`VAN_LOAD` → `HANDED_TO_DROP_VAN`). At stop V5 (07:50), driver + DA-A scan it (`VAN_TO_DA` → `DROP_COLLECTED`); DA-A now carries it for last-mile delivery (M5).
*First-mile:* DA-A also hands the driver 6 parcels she picked up; each is scanned (`DA_TO_VAN` → `HANDED_TO_PICKUP_VAN`) and added to Van 3's return manifest. Back at the hub (09:30), the operator scans them in (`VAN_UNLOAD` → `AT_ORIGIN_HUB`); M7 begins sortation; M6 marks the manifest RECONCILED.

---

## 12. Parcel ↔ Loop Binding & the Forecast-to-Actual Flow

The nightly plan is *forecast*; the day runs on *real* parcels. Binding is the bridge (`M6-D-015`, `-016`, `-017`).

### 12.1 Last-mile binding (deliver)
On `parcel.sorted_for_delivery` from M7: resolve destination hex → DA → that DA's meeting vertex + van. Bind to the **earliest loop with spare capacity** — the next van out — and append to its outbound manifest. The parcel's **delivery deadline** (M4 SLA) is recorded on the manifest item for M10 but is **advisory, not a gate** (see §12.3): a late or missing deadline still binds the soonest loop. Overflow (§12.4) only if **no loop has room**.

### 12.2 First-mile binding (collect)
A DA accumulates PICKED_UP parcels through the day. On each pickup, bind to the **earliest loop with spare capacity** — the next van back — so the parcel reaches the hub fastest. Its **hub-arrival deadline**, derived from the committed flight cutoff (M9) minus the end-to-end tail `cutoff − (sort + bag + shuttle)` (the Q2 budget), is recorded on the item for M9/M10 but, like deliver, is **advisory**: if M9 gives no cutoff it falls back to the window end, and a missed cutoff still binds the soonest loop (the parcel rides to the hub and waits for the next flight). Overflow only if no loop has room.

### 12.3 The combined binding rule (`M6-D-016`)
> **v1 (shipped) = fastest-greedy, deadline-advisory.** Both directions bind the **earliest loop with live capacity** (deliver: next van out; collect: next van back), in arrival order. The SLA deadline / flight cutoff is **stored on the item, never used to gate** — M9 is unbuilt and the deliver SLA accuracy is unproven, so an untrusted number must not block physical movement. A late, or absent, deadline still binds; **overflow fires only on capacity exhaustion** (§12.4). M10/M11 own the SLA-breach reaction, reading the deadline already on the item — M6 invents no breach event.

> **Post-v1 (deferred): SLA-first reordering + reactive bump.** When `city_fleet_config.capacity_packets` is lowered to real van capacity and loops actually fill, the target is deadline-first ordering (a tight parcel never waits behind a slack one) plus bumping the slackest already-bound parcel to make room. Capacity is configured high in v1 so this never needs to fire; the seams (`LoopSlot`, per-loop capacity guard, `bindEarliest` core) are in place for an additive change with no rewrite.

### 12.4 Runtime overflow / back-pressure (`M6-D-017`)
Capacity per loop is fixed by the locked plan. Overflow means exactly one thing in v1 — **no loop has room** (a missed/absent deadline never causes it):
1. **Bump** the slackest already-bound parcel to a later loop to make room. *(Post-v1; deferred — see §12.3. v1 skips straight to step 2.)*
2. If a parcel has **no loop with room** ⇒ raise `LOOP_OVERFLOW` → station manager + M10; recommend an ad-hoc extra van (intraday van add needs approval, NFR-3) or a direct hub run.
3. Never silently drop a parcel — overflow escalates, it does not discard (mirrors M7 hub overload, PRD §9.5).

### 12.5 Why this can't be planned away
Forecast (70/30) sizes loops well on average, but a demand spike, a flight pulled earlier, or DA pickups clustering late in the day all create real-time pressure the nightly plan can't see. §12.4 is the safety valve; §13 handles the physical failures.

---

## 13. The Handoff Protocol & Failure Handling

### 13.1 The stop protocol (happy path, `M6-D-018/-019`)
1. Van arrives at vertex Vk (`VAN_ARRIVED`); a **dwell window** opens (`dwell_minutes`, configurable; default ~5 min).
2. For each DA assigned to Vk (within the window): driver scans **out** that DA's deliveries (`VAN_TO_DA`), DA scans them **in** (M5); DA scans **in** her collections to the van (`DA_TO_VAN`).
3. **Reconcile per DA:** expected (manifest, filtered by `stop_seq` & `da_id`) vs scanned. Match ⇒ items HANDED_OFF/ONBOARD. Mismatch ⇒ §13.3.
4. Driver confirms the stop; van departs (`VAN_DEPARTED` implicit). `HANDOFF_COMPLETED` event per stop.

### 13.2 Multi-DA & late-DA (`M6-D-019`)
Several DAs share Vk; they meet within the dwell window. A DA later than the window:
- her **deliveries** are carried on the van and re-attempted on the **next loop** (re-bound, §12.1);
- her **collections** miss this loop and are bound to the next feasible loop (§12.2);
- if either breach a deadline ⇒ escalate. The van **does not wait** past the dwell window — cycle time is hard (C3).

### 13.3 Reconciliation discrepancies (`M6-D-018`)
At a stop, per DA: **missing** (manifest parcel not scanned), **extra** (scanned parcel not on manifest — mis-route), **rejected** (DA refuses, e.g. damaged). Each raises `HANDOFF_DISCREPANCY` → M11 ticket + M10 SLA flag; the item goes `EXCEPTION`; physical parcel handled per type (carry-back, return to sort, quarantine).

### 13.4 Failure modes (`M6-D-021`)

| Failure | Detection | Response | Escalation |
|---------|-----------|----------|------------|
| **DA no-show** at the stop | dwell window expires, no DA scan | deliveries carried back / next loop; collections deferred | if deadline at risk → M10 + M11; station manager |
| **Van breakdown** mid-loop | telemetry stalls / driver reports | dispatch **recovery van** to transfer the manifest at the van's position; recompute affected ETAs/deadlines | `VAN_BREAKDOWN` → M10 + station manager; ad-hoc van needs approval |
| **Mis-sort** (wrong parcel on van) | load reconciliation or stop "extra" | carry back to hub sort; rebind | `HANDOFF_DISCREPANCY` → M11 |
| **Lost / damaged on van** | reconciliation shortfall at unload | van leg owns the gap (custody node, `M6-D-014`); investigation | M11; attributed to van/driver |
| **Loop overflow** | binding can't fit a parcel | §12.4 bump/escalate | `LOOP_OVERFLOW` → M10 + station manager |

### 13.5 Recovery van
A recovery van is an unplanned vehicle dispatched on station-manager approval to take over a broken van's manifest. M6 transfers the manifest (custody re-assigned to the recovery van's ID, append-only), recomputes deadlines, and rebinds any now-infeasible parcels (§12.4). This is the only sanctioned intraday fleet change (NFR-3).

---

## 14. Live Van Tracking & Telemetry

Run-time half of M6 (`M6-D-011`). Plan-time says "Van 1 *will* reach V5 at 07:50"; run-time says "Van 1 *arrived* V5 at 07:58 — 8 min late."

### 14.1 Telemetry travels by HTTP, never Kafka (`M6-D-012`)
The van-driver app is a **separate mobile client**. It POSTs to an M6 endpoint — Kafka stays private inside our infra.

```
[Van phone] every ~10s
  POST /api/v1/van/{vanId}/telemetry  { lat, lon, type, time, [parcel_scan], [da_id] }
      │  (ordinary HTTPS)
      ▼
[Monolith] routing/api/VanTelemetryController
      │  (in-process — same app, free)
      ▼
  routing/service/VanTrackingService   → update live position; compare to plan; reconcile scans
      │  ONLY on meaningful change
      ▼
  routing/events/*Producer ──► Kafka oneday.cron.events
      │
      ▼  M5 (cron feasibility) · M10 (SLA) · M4 (custody state) · M11 (exceptions) · ops map
```

- **Monolith intact:** a phone client doesn't make the backend non-monolithic; it's just a client.
- **One API door:** `POST .../telemetry` carries GPS pings *and* scan events (`type` discriminates).
- **Raw GPS pings stay in-process;** only `VAN_ARRIVED`, `VAN_RUNNING_LATE`, `HANDOFF_COMPLETED`, `HANDOFF_DISCREPANCY`, `VAN_BREAKDOWN`, `LOOP_OVERFLOW` reach Kafka.

### 14.2 Components (`routing` module)

| File | Role |
|------|------|
| `api/VanTelemetryController` | the HTTPS door the app POSTs to (GPS + scans) |
| `service/VanTrackingService` | brain: position, plan-vs-actual ETA, scan reconciliation, deviation decision |
| `service/VanManifestService` | build/seal manifests, bind parcels (§12), apply overflow |
| `domain/VanLiveStatus` | latest position + lateness per van (overwritten; powers the map) |
| `events/RouteDeviationProducer` / `HandoffProducer` | publish run-time events |

```java
@Service
class VanTrackingService {
    void handle(UUID vanId, VanTelemetryEvent e) {
        liveByVan.get(vanId).update(e.lat(), e.lon(), e.time());        // 1. always: position
        switch (e.type()) {
            case GPS -> {}                                              //    nothing else
            case ARRIVED_AT_STOP -> openDwellAndCheckLateness(vanId, e);// 2. ETA vs plan
            case DELIVER, COLLECT -> manifest.reconcileScan(vanId, e);  // 3. custody (§11)
            case DEPARTED_STOP -> manifest.closeStop(vanId, e);         //    reconcile stop
        }
    }
}
```

### 14.3 Downstream — M5 (re-check cron feasibility on lateness), M10 (SLA), M4 (custody transitions), M11 (discrepancies), ops/M4 (live map from `VanLiveStatus`).

### 14.4 Plan-vs-actual ETA
On `ARRIVED_AT_STOP`, lateness = actual − planned; the slip propagates to this van's remaining stops; if it threatens any DA handoff or a parcel deadline ⇒ `VAN_RUNNING_LATE` with the new ETAs.

### 14.5 Cost — effectively free
~100 vans total (≈20×5). API ≈ 10 req/s (trivial); raw pings never on Kafka, only ~few-thousand meaningful events/day (cents); only latest position stored (overwrite); mobile data a few MB/van/day (driver's plan, batchable). The trap to avoid: fanning raw pings through a per-message queue.

---

## 15. Van-Driver App Workflow

M6 owns the driver-side contract (`M6-D-020`), parallel to M5's DA app. The app is a thin client over §17.2 APIs.

```
1. LOGIN            M1 van-driver role, city-scoped JWT → today's route + loop timetable.
2. LOAD (at hub)    App lists this loop's outbound manifest. Driver scans each parcel onto the van
                    (VAN_LOAD). App enforces reverse-stop-order loading. Manifest sealed → LOADED.
3. DEPART           Driver confirms; VAN_DEPARTED_HUB. Background GPS pings begin (~10s).
4. NAVIGATE         Turn-by-turn to next vertex (OSRM/maps). ETA shown vs plan.
5. AT STOP          App shows, per DA at this vertex:
                      • DELIVER list (scan out, hand over)          → VAN_TO_DA
                      • COLLECT (scan in DA's parcels)              → DA_TO_VAN
                    Per-DA reconcile; flag missing/extra/rejected. Confirm stop.
6. REPEAT 4–5 for all stops in the loop.
7. RETURN (hub)     Scan collections off the van (VAN_UNLOAD). Manifest → RETURNED.
                    Hub operator (M7) takes custody → AT_ORIGIN_HUB; manifest RECONCILED.
8. NEXT LOOP        Repeat from 2 for the next loop, all day.
EXCEPTIONS          Report breakdown / discrepancy from any screen → §13.
```

The app talks only to M6 over HTTPS; M6 emits the Kafka events others consume. The driver never sees Kafka.

---

# Part III — Integration & Governance

## 16. Cross-Module RACI & Contracts

The custody chain crosses M5/M6/M7/M8/M4/M9/M11. Drawing this boundary first is what unblocks everything in Part II.

| Concern | M6 (van) | M5 (DA) | M7 (hub) | M8 (scan) | M4 (state) | M9 (air) |
|---------|---------|---------|----------|-----------|-----------|----------|
| Route plan / cron schedule | **Own** | consume | — | — | — | — |
| What's ready to load (deliver) | consume | — | **Own** (sort output) | — | — | — |
| Van load (hub→van) | **Own** manifest | — | physical dock | record scan | transition | — |
| Van→DA deliver handoff | **Own** (driver side) | DA side | — | record scan | transition | — |
| DA→van collect handoff | van side | **Own** (DA side) | — | record scan | transition | — |
| Van→hub unload | **Own** manifest close | — | physical dock + sort | record scan | transition | — |
| Parcel→loop binding | **Own** | feeds accumulation | feeds sort output | — | provides SLA | provides cutoff |
| Live van position / deviation | **Own** | consume | — | — | consume | — |
| Discrepancy / failure ticket | originate | — | — | — | — | — |
| Flight-bag shuttle | **Own** timetable | — | provides bags | record | — | consume cutoff |
| SLA judgement | feed | feed | feed | feed | feed | feed → **M10** |
| Exception workflow | feed | feed | feed | — | — | feed → **M11** |

**Key boundary calls:**
- **The van manifest is M6's**, built from M7's sort output. M7 owns the physical dock; M6 owns "what should be on which van."
- **The van is a scan node in M8.** M6 originates `VAN_LOAD / VAN_TO_DA / DA_TO_VAN / VAN_UNLOAD`; M8 stores them immutably; M4 maps them to state.
- **Handoff is two-owner by side:** M6 owns the driver's scans, M5 owns the DA's scans; reconciliation (matching the two) is M6's at the van level.
- **Integration note (`M6-D-008`):** M5's `da_cron_assignment` holds one meeting time; M6 emits the day's list — M5 stores the list and picks the next, or calls `GET /routing/cron/da/{daId}/next`. To confirm with M5 owner.

---

## 17. Contracts — Events, APIs, Data Model

### 17.1 Events (Kafka)

On the reserved `oneday.cron.events` (`common.kafka.KafkaTopics.CRON_EVENTS`, `// M6`), discriminated by a new `common` enum `CronEventType`:

```
CronEventType:
  // ── plan-time ──
  DA_CRON_SCHEDULED      DA's vertex + day's meeting times                 → M5
  SHUTTLE_SCHEDULED      hub↔airport shuttle timetable                     → M9, M10
  ROUTE_PLAN_PUBLISHED   a city's van plan is approved & active            → M10, van app
  ROUTE_CHANGED          intraday override took effect                     → M5, M10, station mgr
  // ── run-time: tracking ──
  VAN_ARRIVED            van reached a meeting vertex                      → M10, ops
  VAN_RUNNING_LATE       live ETA slipped past threshold                   → M5, M10, ops
  // ── run-time: custody & failures ──
  HANDOFF_COMPLETED      a stop's per-DA handoff reconciled OK             → M4, M10
  HANDOFF_DISCREPANCY    missing/extra/rejected parcel at a stop           → M11, M10
  LOOP_OVERFLOW          a parcel can't fit a feasible loop before deadline→ M10, station mgr
  VAN_BREAKDOWN          van disabled mid-loop                             → M10, station mgr, M11
```

Custody **scans** are written to **M8's `scan.event` ledger** (`VAN_LOAD/VAN_TO_DA/DA_TO_VAN/VAN_UNLOAD`), not duplicated on `cron.events`. Raw GPS pings are **not** events (`M6-D-012`).

**`DA_CRON_SCHEDULED` payload:**
```json
{ "cityId":"...","validDate":"2026-06-07","daId":"...","cronVertexId":"...",
  "meetingLat":12.97,"meetingLon":77.61,
  "meetingTimes":["07:30","10:00","12:30","15:00","17:30"],
  "vanId":"...","routePlanId":"..." }
```

### 17.2 REST API

```
# planning / queries
GET  /routing/plans/{cityId}?date=                 plan + recommended_van_count
GET  /routing/plans/{cityId}/vans/{vanId}/stops    ordered stops + ETAs
GET  /routing/cron/da/{daId}?date=                 DA vertex + meeting times (M5)
GET  /routing/cron/da/{daId}/next?at=              single next meeting (M5 convenience)
GET  /routing/shuttle/{cityId}?date=               shuttle timetable (M9)
POST /routing/plans/{planId}/approve               station mgr / admin (city-scoped, M1)
POST /routing/plans/{planId}/override              append-only revision + audit
POST /routing/plans/{cityId}/replan                force re-solve (admin)
# manifest / execution
GET  /routing/vans/{vanId}/manifest?loop=          this loop's load/collect manifest (driver app)
POST /routing/vans/{vanId}/recovery                dispatch recovery van (station mgr, §13.5)
# run-time
POST /api/v1/van/{vanId}/telemetry                 GPS pings + DELIVER/COLLECT scans (driver app)
GET  /routing/vans/{cityId}/live                   live positions + lateness (ops map)
```

### 17.3 Data model (Flyway `V6_*`)

```
city_logistics_node        id, city_id, kind(HUB|AIRPORT), lat, lon, name              [M6-D-007]
city_fleet_config          id, city_id, vans_available, capacity_packets,
                           cycle_time_min/max_minutes, shuttle_cadence_minutes,
                           max_da_to_vertex_minutes, dwell_minutes                      [M6-D-005/019]

route_plan                 id, city_id, valid_for_date, status(PROPOSED|APPROVED|SUPERSEDED|REJECTED),
                           source(NIGHTLY|MANUAL_OVERRIDE|FALLBACK), solver_type,
                           vans_used, recommended_van_count, provisioning_flag,
                           n_loops, realised_cycle_minutes, notes, approved_by/at, created_at  [append-only]
route_plan_stop            id, route_plan_id, van_id, loop_index, stop_seq,
                           node_kind(HUB|MEETING_VERTEX), hex_vertex_id,
                           planned_arrival/departure, deliver_qty, collect_qty, load_after
da_cron_schedule           id, route_plan_id, da_id, hex_vertex_id, van_id,
                           meeting_times(jsonb), city_id, valid_date
route_override_audit       id, route_plan_id, actor_id, action, before_json, after_json, reason, created_at [append-only]

# ── execution / custody (Part II) ──
van_manifest               id, route_plan_id, van_id, loop_index, valid_date,
                           status(BUILDING|LOADED|IN_PROGRESS|RETURNED|RECONCILED),
                           departed_at, returned_at                                     [M6-D-015]
van_manifest_item          id, manifest_id, parcel_id, direction(DELIVER|COLLECT),
                           stop_seq, meeting_vertex_id, counterparty_da_id, sla_deadline,
                           status(PLANNED|LOADED|ONBOARD|HANDED_OFF|RECONCILED|EXCEPTION),
                           loaded_at, handed_off_at                                     [M6-D-014]
handoff_reconciliation     id, manifest_id, stop_seq, da_id, expected_count, actual_count,
                           discrepancy_type(MISSING|EXTRA|REJECTED|NONE),
                           discrepancy_parcel_ids(jsonb), reason, created_at            [append-only, M6-D-018]
van_live_status            van_id(pk), city_id, route_plan_id, last_lat, last_lon,
                           last_seen_at, current_stop_seq, minutes_late                 [overwritten, M6-D-012]
```

Custody scans live in **M8's ledger**, referenced by `parcel_id` + scan type — not duplicated here. `h3_hex_travel_time` is not reused (`M6-D-009`).

---

## 18. Constraint Catalogue

| # | Constraint | Hard/Soft | Notes |
|---|-----------|-----------|-------|
| C1 | Coverage — every territory reachable from ≥1 visited vertex | Hard | set-cover §7.2 |
| C2 | Capacity — peak load ≤ `capacity_packets` (VRPSPD) | Hard | M6-D-002 |
| C3 | Cycle time — loop span ≤ `cycle_time_max` (3h), target 2h | Hard / soft | M6-D-003; van never waits past dwell (C7) |
| C4 | Hub anchoring — every loop starts & ends at hub | Hard | depot |
| C5 | DA reachability — DA→vertex ≤ `max_da_to_vertex_minutes` | Hard | NFR-4 |
| C6 | Meeting-time spacing ≥ M5 `CRON_FREEZE_MINUTES` (30m) | Hard | M5 §8.3 |
| C7 | Dwell window — bounded per stop (`dwell_minutes`) | Hard | M6-D-019 |
| C8 | 70/30 demand weighting | — | M6-D-004 |
| C9 | SLA-first binding — earliest deadline-feasible loop | Hard | M6-D-016 |
| C10 | Parcel deadline — delivery SLA (last-mile) / cutoff-derived hub-arrival (first-mile) | Hard | M6-D-016; §12.2 budget = Q2 |
| C11 | Capacity overflow → bump then escalate, never drop SLA | Hard | M6-D-017 |
| C12 | Custody continuity — every parcel scanned at all 4 points; never "nowhere" | Hard | M6-D-014 |
| C13 | Handoff reconciliation — expected = actual or exception | Hard | M6-D-018 |
| C14 | Per-parcel cost floor (cost-aware routing) | Soft | M6-D-010; stub until M2 |
| C15 | City scoping — all data carries `city_id`; overrides city-bounded | Hard | NFR-5, M1 |
| C16 | Fleet — route within `vans_available`; also recommend | Hard / advisory | M6-D-005 |
| C17 | Append-only — plans, overrides, reconciliations, scans never mutate | Hard | NFR-1/2 |
| C18 | One sanctioned intraday fleet change — the recovery van, on approval | Hard | M6-D-021, NFR-3 |

---

## 19. Open Questions

| # | Question | Why | Leaning |
|---|----------|-----|---------|
| Q1 | When does the shuttle become flight-cutoff-driven? | M9 coupling | fixed cadence v1; `FlightCutoffPort` |
| Q2 | End-to-end budget: loop + sort(M7) + bag + shuttle ≤ cutoff(M9)? Who owns the split? | makes 2–3h feasible & sets first-mile deadlines (C10) | define with M7/M9/M10 |
| Q3 | Is first-mile vs last-mile demand directional/asymmetric per territory? | peak-load maths | symmetric v1; refine on M4 history |
| Q4 | `max_da_to_vertex_minutes` value | consolidation vs DA util | ~10–12 min; calibrate |
| Q5 | Cycle time (2–3h hard?) & per-city operating window | sets `n_loops`, fleet | 2h target / 3h max, 07:00–20:00, configurable |
| Q6 | One vertex per DA per day, or per-loop variation? | set-cover model | one fixed vertex/day v1 |
| Q7 | One bidirectional fleet, or separate origin/destination vans? | fleet doubling | single bidirectional fleet |
| Q8 | Driver shifts vs 16h window — break constraints? | VRPTW breaks | out of scope v1; flag |
| Q9 | `dwell_minutes` value & late-DA grace | stop choreography (C7, §13.2) | ~5 min; calibrate |
| Q10 | Overflow policy: bump-only vs auto-recommend ad-hoc van threshold | back-pressure (§12.4) | bump + recommend; ad-hoc needs approval |
| Q11 | Recovery-van SLA — how fast must one reach a broken van? | §13.5 | ops policy; flag |
| Q12 | Is the van a distinct M8 scan-location type, or reuse hub/DA types? | M8 schema | new VAN scan node — confirm with M8 |
| Q13 | Mis-sort / rejected-parcel physical handling (quarantine vs immediate return) | §13.3 | with M7/M11 |
| Q14 | Driver identity vs van identity — does custody attach to driver or vehicle? | liability (§13.4 loss) | van + driver on each scan; confirm with M1 |

---

## 20. Phase Plan

| Phase | Deliverable |
|-------|-------------|
| **P1** | Skeleton; `common.CronEventType`; injectable `Clock` (M6-D-013); Flyway `V6_*` (§17.3); seed `city_logistics_node` + `city_fleet_config` for 5 cities. |
| **P2** | Input adapters (M3 territories/vertices/demand); `OsrmClient` node-set matrix; stub ports (`CostFloorPort`, `FlightCutoffPort`, `HubSortPort`, `DaAccumulationPort`). |
| **P3** | Stages 1–2: demand aggregation + meeting-point set-cover. |
| **P4** | Stage 3: OR-Tools CVRP+TW+pickup/delivery; Clarke–Wright fallback. |
| **P5** | Stages 4–5: periodise, per-DA cron, persist plan. |
| **P6** | Fleet-sizing recommendation + `UNDER_PROVISIONED`. |
| **P7** | `NightlyRoutePlanJob` + approval/fallback/override REST + audit. |
| **P8** | Shuttle timetable + `SHUTTLE_SCHEDULED`. |
| **P9** | **Manifest engine** (`VanManifestService`): parcel→loop binding (§12), SLA-first, overflow. |
| **P10** | **Custody scans** (§11): the four scan points → M8 ledger contract + M4 transitions; reconciliation. |
| **P11** | **Handoff protocol & failures** (§13): dwell, multi-DA, discrepancies, no-show, breakdown, recovery van. |
| **P12** | Telemetry ingestion: `VanTelemetryController` + `VanTrackingService` + `VanLiveStatus`; in-process path; live API. |
| **P13** | Plan-vs-actual + run-time event producers (`VAN_ARRIVED/RUNNING_LATE/HANDOFF_*/LOOP_OVERFLOW/VAN_BREAKDOWN`). |
| **P14** | Van-driver app contract (§15) end-to-end against stubs; Kafka producers wired; integration test with mock M5/M7/M8 consumers. |
| **P15** | End-to-end seeded-city run + calibration of Q4/Q5/Q9/Q10 constants. |

---

## 21. Testing & Simulation

You can't put 100 real vans/DAs on the road per change, so M6 (and M5–M11) test against a **synthetic world** — fake producers talking to the *real* modules over the same Kafka/HTTP. The event-bus architecture is what makes this possible: real consumers can't tell synthetic events from real ones.

**21.1 Injectable clock (`M6-D-013`).** All time via a `Clock` bean ⇒ a 16h day runs in seconds; lands in P1 (retrofitting is painful).

**21.2 Fake-world producers (test-only `simulator`).** Only publish events / call public APIs — never touch internals.

| Fake producer | Emulates | Feeds |
|---|---|---|
| Booking generator | bookings | M4 → M5, M2 |
| Virtual DA | DA GPS + pickup/delivery + handoff scans | M5, M6, M8 |
| **Virtual van** | van telemetry (GPS), load/deliver/collect/unload scans | **M6**, M10 |
| Virtual hub | sort output + dock load/unload | M6, M7, M8 |
| Flight feed stub | flight status / cutoff | M9 |
| Failure injector | DA no-show, van breakdown, mis-sort, demand spike | M6, M11, M10 |

**21.3 The virtual van (emulating the tracking + scan API).** Reads a published plan + manifest; interpolates a GPS point per simulated 10s along OSRM geometry; POSTs to the real `/api/v1/van/{vanId}/telemetry`; fires `ARRIVED_AT_STOP`, then `DELIVER`/`COLLECT` scans per the manifest. `VanTrackingService` can't tell it from a real driver. Inject a slowdown ⇒ assert `VAN_RUNNING_LATE` → M5 re-checks feasibility → M10 amber. Inject a missing scan ⇒ assert `HANDOFF_DISCREPANCY` → M11.

**21.4 Test pyramid.** Unit (set-cover, peak-load capacity, binding deadline maths) → Contract (event schemas, Testcontainers Kafka+Postgres, the M3/M4 pattern) → Integration (boot monolith, synthetic events, assert DB + M4 state) → **Simulation ("virtual day")** (seed grid+plan+manifests; N virtual DAs + M virtual vans; accelerated clock; assert invariants: no cron missed unless injected, custody continuity C12 holds, overflow escalates not drops, RTO after exactly N attempts).

**21.5 Fault library.** Late van → `VAN_RUNNING_LATE` → M5 defer + M10 amber · van late past dwell → handoff partial + DA next loop · DA no-show → carry-back + escalate · missing/extra scan → `HANDOFF_DISCREPANCY` → M11 · demand spike → `LOOP_OVERFLOW` → bump/escalate · breakdown → recovery van · flight delay → M9 rebind first-mile deadlines.

**21.6 Rollout ladder.** **Simulation (offline)** → **shadow** (new planner on real live events, output logged not acted on, compared to live) → **canary** (one city — design is city-scoped) → **full**. The durable Kafka log also enables **replay** of a recorded real day for deterministic before/after comparison. This is the standard Uber/DoorDash dispatch-change playbook.

---

*Draft v0.3. M6 now owns the full van lifecycle: routing + scheduling (Part I), parcel custody + manifest + binding + handoff + failures (Part II), and integration/governance (Part III). Decisions M6-D-001…021, constraints C1…C18, open questions Q1…Q14. Sharpest items for next review: the end-to-end timing budget (Q2, gates first-mile deadlines), the M6↔M7↔M8 custody boundary (§16), and the overflow/recovery policies (Q10–Q11).*
