# M3 + M6 — Knowledge Transfer & Hands-On (for the M5 builder)

> **Purpose.** You've built M1/M2/M4 and are about to build **M5 (dispatch)**. M5 sits on top of
> M3 (the spatial grid) and is fed by M6 (van routing). This doc gives you an end-to-end mental
> model of M3 and M6, a hands-on playbook on the demo UI (what to click, what to observe), a list
> of what the UI does *not* show you (and where to look instead), and — the part that matters most
> for you — **exactly what M5 will need from M3/M6 and what M5 must give back to the rest of the
> system.**
>
> Read top to bottom once, then keep §7 (the M5 contract) open while you design.

> 🧭 **Reading this from the build plan?** The
> [M5 Implementation Plan](M5-Implementation-Plan.md) links into specific sections here for prior
> M3/M6 context. After reading a section, **↩ jump back to the
> [M5 Implementation Plan](M5-Implementation-Plan.md)** (or use your editor/browser Back button to
> land exactly where you left off). Back-links repeat at the end of each major section below.

---

## 0. The one-paragraph mental model

> A parcel's first/last mile is a chain: **pickup DA → hub → flight → hub → delivery DA**. Two
> modules own the *ground* part of that chain's geometry and movement. **M3** carves each city into
> a fixed grid of **H3 hexagons**, decides nightly **which Delivery Associate (DA) owns which
> hexes** (a "territory"), and answers "do we serve this lat/lon?". **M6** plans the **consolidation
> vans** — the shuttles that carry parcels between the **hub** and the **DAs** out in their
> territories, meeting each DA at fixed **meeting vertices** (hex corners) several times a day on a
> repeating loop. **M5 (you)** is the DA-side brain: it takes orders, assigns them to the right DA
> (using M3's territory map), sequences each DA's pickups/deliveries, and makes sure each DA can
> reach their van's meeting time (using M6's cron schedule). M3 is *where*, M6 is *how parcels move
> between hub and DA*, M5 is *what each DA does and when*.

```
        M3 (grid)                 M5 (dispatch — YOU)              M6 (van routing)
   ┌──────────────────┐      ┌──────────────────────┐      ┌────────────────────────┐
   │ city → H3 hexes   │ ───▶│ order → which DA?      │      │ where do vans go,       │
   │ DA → hexes (terr.)│ ───▶│ sequence DA's stops    │◀──── │ when do they meet DAs   │
   │ serviceable?      │      │ DA reach van in time?  │      │ (per-DA cron schedule)  │
   │ vertices (corners)│ ───▶ (M6 uses these)          │      │ custody on the van      │
   └──────────────────┘      └──────────────────────┘      └────────────────────────┘
```

---

<a id="kt-h3"></a>
## 1. ⚠️ Read this first — H3 hexagons, not rectangular tiles

The big design doc `docs/M3/M3-GRID-DESIGN.md` describes **fixed 2 km × 2 km rectangular tiles**.
**That design was superseded.** The *implemented* M3 (and everything M6/the demo uses) is **Uber
H3 hexagons in WGS84**. So when you read the old doc:

| Old doc says (rectangular) | Implemented reality (H3) | Where it lives |
|---|---|---|
| `tile`, `row_idx/col_idx` | **`hex`** with an H3 index | `grid.domain.Hex` |
| `tile_demand_snapshot` | **`h3_hex_demand_snapshot`** | `grid.domain.HexDemandSnapshot` |
| `da_tile_assignment` | **`da_hex_assignment`** | `grid.domain.DaHexAssignment` |
| `tile_travel_time` | **`h3_hex_travel_time`** | `grid.domain.HexTravelTime` |
| `grid_vertex` (M+1×N+1 corners) | **`h3_hex_vertex`** (the 6 corners of each hex) | `grid.domain.HexVertex` |
| `getTileAt` integer division | H3 library `latLngToCell` | `GridService.serviceableAt(...)` |

The **algorithm and concepts are identical** — demand-in-minutes, balanced-territory partition,
road-time adjacency via OSRM, nightly propose → approve → activate, human override. Only the cell
*shape* changed (rectangles → hexagons) because hexes give uniform neighbour distances and better
spatial uniformity. **Read the old doc for the *why* (it's excellent on the algorithm), but trust
the H3 entity names above for the *what*.** The refactor is tracked in
`docs/M3/M3-H3-REFACTOR-PLAN.md`.

The UI calls them "hexes" everywhere — good, that's the truth.

---

<a id="kt-messaging"></a>
## 1b. ⚠️ Read this second — the event bus is RabbitMQ, not Kafka

The codebase was refactored **Kafka → RabbitMQ** (commit "Rabbit Mq from kafka refactor").
Wherever older docs (and earlier drafts of this one) say "Kafka", read **RabbitMQ**. Concretely:

| Old (Kafka) | Current (RabbitMQ) | Where |
|---|---|---|
| `KafkaTopics.java` | **`EventStreams.java`** (constants = RabbitMQ **topic exchanges**) | `common.kafka.EventStreams` |
| `KafkaTemplate` / direct producer | **`EventPublisher.publish(EventStreams.X, event)`** — inject the port, never the broker | `common.kafka.EventPublisher` → `RabbitEventPublisher` |
| `@KafkaListener` + consumer groups | **`@RabbitListener(queues=…)`** + a `*MessagingTopology` `@Configuration` that declares the queue/binding | e.g. `grid.events.GridMessagingTopology` |
| topic + partition key | **exchange + routing key** — the event's `DomainEvent.eventTypeName()` (the enum value) is the routing key | `DomainEvent` |
| `@EmbeddedKafka` tests | Testcontainers **RabbitMQ** (or topology unit tests) | — |

Every event payload is a **record implementing `DomainEvent`** (it exposes `eventTypeName()` →
routing key, and `partitionKey()` for stream ordering). The package is still
`common.kafka.*` (name kept; transport swapped). Full picture: `docs/EVENT-BUS-ARCHITECTURE.md`.

---

## 2. M3 — the grid, in depth

### 2.1 Two jobs M3 owns
1. **Serviceability** — given a (city, lat/lon) or (city, pincode), *do we deliver there?* and
   *which hex is it?*. M4 already calls this at booking (`ServiceabilityPort`). **You don't rebuild
   this; you consume it.**
2. **Grid management** — the fixed H3 geometry per city, plus the **nightly DA→hex assignment**
   (territories) and the **vertices** M6 routes over.

<a id="kt-demand"></a>
### 2.2 The core unit: "demand in minutes" (not order count)
This is the single most important M3 idea, and it propagates into M5 and M6. A hex's demand is
**estimated order-engaged DA minutes**, not a parcel count:

```
order_engaged_min_per_order = service_time (mins at the customer's door)
                            + inter_stop_travel (mins driving between two pickups in the hex)

demand_minutes(hex) = (0.70 × 7-day-avg-orders + 0.30 × orders-today) × order_engaged_min_per_order
```

- **70/30 weighting** (historical/current) is a system-wide invariant — M3, M5, M6 all use it.
- A DA's capacity is also in minutes: `shift × 0.70` is the target load (the **70% utilisation**
  rule — 70% order-engaged, 30% idle/repositioning). That's the *cost floor*; don't optimise purely
  for speed.
- **Bootstrap mode:** before M4 GPS data exists, hexes use flat defaults (service 12 min,
  inter-stop 5 min). In the demo you **seed** these minutes directly (the "min/max minutes"
  sliders), because M4 isn't producing live GPS timings yet.

### 2.3 The nightly territory assignment (what "Generate territories" runs)
1. Compute `demand_minutes` per active hex (in the demo: from the seeded snapshot).
2. Load the **road-time adjacency graph** from `h3_hex_travel_time` (OSRM-precomputed — two hexes
   are "neighbours" only if road travel time ≤ threshold; a river/highway between geometrically
   adjacent hexes makes them *not* neighbours).
3. **The territory solver** partitions hexes into K DA territories that are **load-balanced**
   (minimise max−min load) **and contiguous** (each DA's hexes form one connected blob).
   **Reality check:** the *active* solver wired into the nightly replan is
   **`BalancedBfsAssignmentServiceImpl`** (`@Qualifier("balancedBfsAssignmentService")`), not
   CP-SAT. A full `CpSatAssignmentServiceImpl` (OR-Tools, lazy-cuts contiguity) exists in the tree
   but is not the path that runs. The old design doc describes the CP-SAT formulation; the running
   system produces the same *kind* of result via balanced BFS.
4. Output an **`AssignmentProposal`** (status `PROPOSED`) with per-DA regions, util %, solver
   metadata (CP_SAT vs BFS fallback, optimality gap, bootstrapped-hex flags).
5. **Human approval** → `APPROVED` → **activate** → `ACTIVE`. M6 reads only `ACTIVE` assignments.

> The append-only / nightly-stability invariants live here: proposals and assignments are never
> mutated, only superseded; territories replan once nightly; intraday changes need station-manager
> approval (`INTRADAY_OVERRIDE` = move hexes between DAs; `INTRADAY_SHARE` = add a 2nd DA to a hex).

### 2.4 Vertices — the bridge to M6
Every hex has 6 corner **vertices** (`HexVertex`, with lat/lon + H3 index). M6 picks a subset of
these — the ones on boundaries **shared by several DA territories** — as **meeting points** where a
van can serve multiple DAs in one stop. M3 just *exposes* the vertices; M6 selects among them.

### 2.5 M3 data model you'll touch (H3 names)
| Entity / table | What it holds | Why M5 cares |
|---|---|---|
| `Grid` / `grid` | one row per city: H3 resolution, boundary | — |
| `Hex` / `hex` | one row per H3 cell: city, hexId, h3Index, active | resolve GPS → hex |
| `HexDemandSnapshot` / `h3_hex_demand_snapshot` | per hex per date: demand_score_minutes, service_time_min, is_bootstrapped | **service_time_min for cron feasibility** |
| `DaHexAssignment` / `da_hex_assignment` | per DA per hex per date: status (PROPOSED/ACTIVE/SUPERSEDED), n_das_on_tile | **the territory map M5 loads at shift start** |
| `AssignmentProposal` / `assignment_proposal` | the nightly proposal header + metadata | review/approve flow |
| `HexVertex` / `h3_hex_vertex` | hex corners (lat/lon) | M6 meeting points |
| `HexTravelTime` / `h3_hex_travel_time` | OSRM road-time adjacency | solver input (not M5's) |

<a id="kt-m3-apis"></a>
### 2.6 M3 APIs you'll actually call (from M5)
The design doc §7 lists the full surface. The ones M5 needs (some may need a small addition — see
§7.1 caveats):
> **Real paths** (verified in `grid.api.GridController`, base `/api/grid`): all are
> `/api/grid/{cityCode}/…` except the top-level coordinate resolver. The `/grid/...` forms in the
> old design doc are stale.

- `GET /api/grid/serviceable-at?lat=&lon=` → resolve a coordinate to `{city, hexId, h3Index, serviceable}`
  (top-level, no cityCode). **Better still, in-process:** M3 implements
  `common.port.ServiceabilityPort.check(ServiceabilityQuery)` — M4 already uses it; per the
  cross-module rule M5 should prefer this port over HTTP.
- `GET /api/grid/{cityCode}/assignments?date=` → per-DA `{da_id, hex_ids[]}` — **the territory map**.
- `GET /api/grid/{cityCode}/tiles/{hexId}/load-score` → live overload signal (`adjusted_load_score`).
- `GET /api/grid/{cityCode}/serviceability` and `/api/grid/{cityCode}/tile-at` also exist.
- Intraday-change notification → M3 produces on the **`GRID_EVENTS`** exchange
  (`EventStreams.GRID_EVENTS`, `GridEventType` discriminator). Confirm an "assignment updated"
  routing key is emitted on intraday override; else M5 polls `/assignments`.

---

> ↩ **[Back to the M5 Implementation Plan](M5-Implementation-Plan.md)** (end of the M3 section).

## 3. M6 — van routing, in depth

M6 is **much bigger than the demo shows.** It has two halves; **only the first is built/visible.**

<a id="kt-m6-plan"></a>
### 3.1 Part I — Plan-time (nightly batch) — *this is what the demo runs*
"Where do vans go, and when." Per city, per day:
1. **Demand aggregation** — sum each DA territory's `demand_score_orders`; split first-mile/last-mile
   (50/50 in v1); per-loop demand = daily ÷ n_loops.
2. **Meeting-point selection (set cover)** — pick the fewest hex vertices such that every DA
   territory has ≥1 reachable meeting vertex, biased to high-degree (shared) vertices, bounded by
   `max_da_to_vertex_minutes` (protects the DA's 70% utilisation).
3. **The VRP** — Google **OR-Tools `RoutingModel`** solves a capacitated, time-windowed,
   simultaneous-pickup-and-delivery vehicle routing problem over `{hub} ∪ meeting-vertices`:
   - **Capacity** = peak instantaneous load ≤ `capacity_packets` (load goes up as it collects, down
     as it delivers — the binding limit is the *peak*, not the net).
   - **Time** = loop span ≤ `cycle_time_max` (target 2 h, max 3 h).
   - Every loop starts and ends at the **hub**.
   - **Drop-and-flag** (`drop-infeasible-vertices`): far corners no van can reach within the cycle
     are **deferred** (red ✕ on the map, stored in `route_plan.deferred_vertex_ids`) rather than
     slowing the whole fleet. Lower cycle → more deferred; raise it → fewer.
4. **Periodise** — `n_loops = floor(window / cycle)`; stamp wall-clock ETAs per (van, loop, stop).
   Each van runs its *own* cadence now, so loops/day & cycle vary across the fleet (shown as a range).
5. **Persist + cron** — write `route_plan` (PROPOSED), `route_plan_stop`, and **`da_cron_schedule`**
   (per DA: vertex + the day's meeting times). Approve → APPROVED → routes lock.

Plus **fleet sizing** (`M6-D-005`): you give `vans_available`; M6 also computes a
`recommended_van_count`; if you under-provision, the plan is flagged `UNDER_PROVISIONED`.

And the **hub↔airport shuttle** — a simple periodic timetable (not a VRP), carrying sealed flight
bags. (`GET /routing/shuttle/{cityId}` — not surfaced in the demo UI.)

<a id="kt-m6-runtime"></a>
### 3.2 Part II — Run-time (all day) — *NOW PARTLY BUILT on `main`* ⚠️
> **UPDATE (post-merge):** M6 Phase 5–6 landed on `main`. The custody/handoff backbone is **built**:
> `CustodyService` (4-state ledger — find bound state, check move legal, advance, seal manifest),
> `HandoffService` (DA↔van reconciliation → `HANDOFF_COMPLETED`/`HANDOFF_DISCREPANCY`),
> `VanManifestService` (load/bind/unload), `RecoveryService` (van breakdown). The event wires are
> live too: `CronEventProducer` (→ `CRON_EVENTS`), `HubFeedConsumer`, and **`DaFeedConsumer` which
> consumes our `oneday.da.events`** (see §7.3 for the contract you must match). The van-driver UI and
> live telemetry (`VAN_ARRIVED`/`VAN_RUNNING_LATE`) are still pending.

This is the half the demo does **not** show, and it's the half **you (M5) integrate with at
run-time.** Know it exists:
- **The van is a mobile mini-hub / cross-dock.** Every van↔DA meeting is a *simultaneous, two-way,
  parcel-level custody transfer*: van **delivers** last-mile parcels to the DA, DA **collects**
  first-mile parcels onto the van.
- **Four custody scan points** (each an M8 scan + an M4 state transition):
  `VAN_LOAD` (hub→van) · `VAN_TO_DA` (deliver) · `DA_TO_VAN` (collect) · `VAN_UNLOAD` (van→hub).
- **Van manifest** — which specific parcels ride which van/loop, built from *actuals* at load time.
- **Parcel→loop binding** — SLA-first, capacity-bounded; overflow → bump then escalate.
- **Handoff protocol** — bounded **dwell window** per stop; the van **never waits** past it; a late
  DA gets a partial/zero handoff and catches the next loop.
- **Live tracking** — van telemetry over HTTP; emits `VAN_ARRIVED` / `VAN_RUNNING_LATE` etc.

> **Why this matters to you:** M5 owns the *DA side* of the deliver/collect handoff. The DA app
> (M5) and the van-driver app (M6) scan the *same* transfer from two sides; M6 reconciles them.
> When you build M5's handoff and cron-feasibility logic, the M6 *contract* (events + cron schedule)
> is what you code against — even though M6's run-time engine isn't built yet (Phase P9–P15).

### 3.3 M6 key decisions you should remember
- `M6-D-003` route shape fixed nightly, loops repeat on cadence (PVRP).
- `M6-D-004` reuses M3's demand snapshot — **one demand truth** across M3/M5/M6.
- `M6-D-008` the per-DA cron schedule carries the **full day's meeting times** (a *list*), not one.
  M5 today models a single `scheduled_meeting_time` — **this is a known integration gap you'll
  resolve** (store the list, pick the next).
- `M6-D-009` M6 builds its *own* OSRM travel matrix (over vertices+hub+airport), not M3's
  centroid→centroid one.

### 3.4 M6 data model (Flyway `V6_*`)
| Table | Holds | M5 relevance |
|---|---|---|
| `city_logistics_node` | hub + airport lat/lon per city | — (M6 owns) |
| `city_fleet_config` | vans, capacity, cycle, dwell, `max_da_to_vertex_minutes` | — |
| `route_plan` | plan header: status, vans_used, recommended, provisioning_flag, n_loops, realised_cycle, deferred_vertex_ids | — |
| `route_plan_stop` | per (van, loop, stop): node_kind, hex_vertex_id, planned arrival/departure, deliver/collect qty, load_after | — |
| **`da_cron_schedule`** | per DA: hex_vertex_id, van_id, **meeting_times (jsonb)**, valid_date | **★ M5 reads this — the DA's meeting times** |
| `van_manifest` / `_item` | run-time custody (Part II) | future handoff integration |
| `handoff_reconciliation` | run-time discrepancies (Part II) | future |
| `van_live_status` | live position/lateness (Part II) | future |

<a id="kt-m6-events"></a>
### 3.5 M6 APIs / events (relevant to M5)
- **Event `DA_CRON_SCHEDULED`** (✅ implemented) on `EventStreams.CRON_EVENTS` — M6 → M5: "DA X
  meets van Y at vertex V at times [...] tomorrow." Typed payload
  `common.kafka.events.cron.DaCronScheduledEvent`:
  `(cityId UUID, validDate, daId, cronVertexId, meetingLat, meetingLon, meetingTimes List<LocalTime>,
  vanId, routePlanId)`. Note `meetingTimes` is a **list** (M6-D-008) and `cityId` is a **UUID** (not a
  short code). **This is your nightly input from M6.**
- `GET /routing/cron/da/{daId}?date=` and `/next?at=` — query a DA's meeting times (M5 convenience).
- Run-time events you'll consume later: `VAN_RUNNING_LATE` (re-check DA cron feasibility),
  `HANDOFF_COMPLETED` / `HANDOFF_DISCREPANCY`.

---

> ↩ **[Back to the M5 Implementation Plan](M5-Implementation-Plan.md)** (end of the M6 section).

## 4. What's in the demo UI — and what's NOT

The demo (`demo-ui`, `OneDay — M6 Route Planning`) is **plan-time only**. Here's the honest map.

### 4.1 In the UI (you can click it)
| UI element | Module | What it does under the hood |
|---|---|---|
| City dropdown | M3 | loads that city's H3 grid + nodes |
| **1 · Seed demand** (min/max mins, seed) | M3 | `POST /api/demo/seed` — writes `h3_hex_demand_snapshot` (synthetic, since M4 GPS absent) |
| **Demand heatmap** view | M3 | colours hexes by demand_minutes |
| **2 · Generate territories** (DA count) | M3 | `replan` → `approve` → `activate` (PROPOSED→APPROVED→ACTIVE) |
| **DA territories** view + Proposal panel | M3 | hexes coloured per DA; per-DA load/util |
| Click a hex → **HexPanel** | M3 | inspect/edit a single hex's demand |
| **3 · Generate routes** (vans, capacity, max-cycle) | M6 | `putFleet` → `m6Replan` → `m6Approve` → load stops/nodes |
| **Van routes** view | M6 | road-snapped loops (OSRM), hub/airport markers, meeting-vertex ETAs |
| Van selector + **Routes panel** | M6 | per-van loops/day, realised cycle, provisioning flag |
| **Covered / Deferred vertices** toggle | M6 | deferred = far corners dropped (drop-and-flag) |

### 4.2 NOT in the UI — and where to see it instead
These are real parts of M3/M6 the demo doesn't surface. To understand the full system you must look
beyond the map:

| Concept | Why it's not in the UI | How to inspect it |
|---|---|---|
| **The per-DA cron schedule** (`da_cron_schedule`) — *the* thing M5 consumes | UI draws routes, not the DA→van timetable | `SELECT * FROM da_cron_schedule WHERE route_plan_id=…` ; or `GET /routing/cron/da/{daId}?date=` |
| **`DA_CRON_SCHEDULED` event** (now implemented) | no broker in local demo (events best-effort) | `routing.events.CronEventProducer.emitDaCronScheduled` publishes `common.kafka.events.cron.DaCronScheduledEvent` on `EventStreams.CRON_EVENTS` |
| **Hub↔airport shuttle timetable** | not wired into the map | `GET /routing/shuttle/{cityId}?date=` |
| **All of M6 Part II** (manifest, 4 custody scans, handoff, dwell, live tracking, overflow, recovery van) | not built (P9–P15) | design doc `M6-ROUTING-DESIGN.md` §11–§15 — **read this, you integrate with it** |
| **M3 serviceability at booking** | demo is grid-planning, not booking | `GET /api/grid/serviceable-at?lat=&lon=` ; the M4 demo UI exercises it |
| **Intraday override / tile-share** (`INTRADAY_OVERRIDE`, `INTRADAY_SHARE`) + `grid.assignment_updated` event | demo only does the nightly path | design §11; `assignment_proposal.proposal_type` |
| **Tile load-score / overload alerts** (live overload) | needs live queue-depth feed (not yet produced) | `GET /api/grid/{cityCode}/tiles/{hexId}/load-score`; M3's consumer (`grid.events.TileQueueDepthConsumer`, `@RabbitListener` on `orders.tile_queue_depth`) is **already built** and waiting — see §7.3 ownership note |
| **OSRM road-time adjacency matrix** (`h3_hex_travel_time`) | it's solver input, invisible | `SELECT count(*) FROM h3_hex_travel_time WHERE grid_id=…` |
| **Bootstrap vs real demand** flag | seeded demand is always synthetic here | `h3_hex_demand_snapshot.is_bootstrapped` |

---

## 5. Hands-on playbook — what to actually do

> App is running: backend `:8080`, UI **http://localhost:5173**. Plan date = **tomorrow**
> (`2026-06-20` in the screenshot). The cloud DB already has the 5 city grids seeded.

### Exercise A — Serviceability (M3's M4-facing job)
Before touching the map, feel the booking-time call:
```bash
# inside Delhi (Connaught Place) → serviceable, resolves to a hex
curl -s "http://localhost:8080/api/grid/serviceable-at?lat=28.6448&lon=77.2167"
# far outside any city → not serviceable
curl -s "http://localhost:8080/api/grid/serviceable-at?lat=20.0&lon=70.0"
```
**Observe:** the first returns `{city, hexId, h3Index, serviceable:true}`. This is the exact contract
M4 uses at booking and **M5 will use to resolve a DA-GPS ping or a pickup address to a hex.**

### Exercise B — The M3 nightly territory flow (seed → territories)
1. Pick **Delhi**. You're on **Demand heatmap** — 3466 active hexes, all grey (no demand yet).
2. **1 · Seed demand**: leave min=4, max=10, type a seed like `42` (reproducible), click. Heatmap
   colours in. *This stands in for M4 GPS demand data.*
3. **2 · Generate territories**: set **DAs = 10**, click. Watch it flip to **DA territories** —
   hexes coloured per DA; the Proposal panel shows per-DA load/util %.
   - **Try:** regenerate with **DAs = 20**. Territories get smaller → more (smaller) meeting
     vertices later → lower per-stop van load. This is the DA-count ↔ territory-size lever.
   - **Inspect a hex:** click one → HexPanel shows its demand_minutes. Edit it, regenerate
     territories (no reseed) — see the partition shift. This is "demand in minutes" made tangible.
4. **Verify in SQL** (against the cloud DB — connect with TablePlus, or psql with the `.env` URL):
   ```sql
   SELECT da_id, count(*) AS hexes, status
   FROM da_hex_assignment
   WHERE valid_date = '2026-06-20' AND status='ACTIVE'
   GROUP BY da_id, status ORDER BY hexes DESC;
   ```
   **Observe:** ~10 DAs, each owning a contiguous blob of hexes. *This GROUP BY is essentially the
   query M5 runs at shift start to load the territory map.*

### Exercise C — The M6 plan flow (territories → routes)
1. With territories live, **3 · Generate routes**: the screenshot shows **Vans=18, Capacity=1000,
   Max cycle=180**. **Start smaller — try Vans=6** (the doc's Delhi reference: 10 DAs + 6 vans →
   ~2 vans used, 2 loops/day). Click.
2. Map flips to **Van routes**: coloured road-snapped loops, **□ HUB** and **✈ AIRPORT** markers,
   numbered meeting-vertex dots.
   - **Hover a dot** → arrival/departure ETA + deliver/collect/load-after. *This is one van↔DA
     meeting — the moment M5's DA must be present.*
   - **Van selector** → filter to one van; Routes panel shows vans-used, recommended, loops/day,
     realised cycle (a *range* — each van runs its own cadence).
   - **Deferred vertices** toggle (red ✕) → far corners the solve dropped. **Lower Max cycle to 120
     and regenerate** → more deferred corners appear (tighter cycle = can't reach the edges).
3. **Try under-provisioning:** set **Vans = 1**, regenerate. You'll likely get *"No feasible routes /
   UNDER_PROVISIONED"* with a `recommended_van_count`. This is `M6-D-005` in action.
4. **Verify the plan + the cron schedule in SQL** (the exit criteria, and the M5-facing output):
   ```sql
   -- plan header
   SELECT status, vans_used, recommended_van_count, provisioning_flag, n_loops, realised_cycle_minutes
   FROM route_plan WHERE valid_for_date='2026-06-20' ORDER BY created_at DESC LIMIT 1;

   -- ★ the per-DA cron schedule — THIS is what M5 consumes
   SELECT da_id, hex_vertex_id, van_id, meeting_times
   FROM da_cron_schedule WHERE route_plan_id='<planId from above>' LIMIT 10;

   -- stops: ETAs should be monotonic per (van, loop); peak load ≤ capacity
   SELECT van_id, loop_index, count(*), min(planned_arrival), max(planned_departure), max(load_after)
   FROM route_plan_stop WHERE route_plan_id='<planId>' GROUP BY van_id, loop_index ORDER BY 1,2;
   ```
   **Observe `meeting_times`** — a JSON array like `["07:30","10:00","12:30",...]`. *Each DA must be
   at `hex_vertex_id` at each of these times. M5's cron-feasibility check exists to guarantee that.*

### Exercise D — Inspect what the UI hides (the M5-relevant bits)
```bash
# the cron schedule for one DA (grab a da_id from the SQL above), as M5 would query it:
curl -s "http://localhost:8080/routing/cron/da/<daId>?date=2026-06-20"
# the shuttle timetable (M9's input, not in the UI):
curl -s "http://localhost:8080/routing/shuttle/<cityId>?date=2026-06-20"
# fleet config:
curl -s "http://localhost:8080/routing/fleet/<cityId>"
```
(City UUIDs are in `M6-DEMO.md`: Delhi = `f47ac10b-58cc-4372-a567-0e02b2c3d479`.)

### Exercise E — Read the two design docs with the demo open
- `docs/M3/M3-GRID-DESIGN.md` §5 (assignment algorithm) — but remember the H3 caveat (§1 here).
- `docs/M6/M6-ROUTING-DESIGN.md` §7 (planning pipeline) and **§11–§16 (the run-time half + RACI)** —
  the RACI table in §16 is the clearest single picture of how M5/M6/M7/M8 share the custody chain.

---

## 6. The cross-module RACI (who owns what at the M5↔M6 boundary)

From `M6-ROUTING-DESIGN.md §16` — the boundary you'll code against:

| Concern | M6 (van) | **M5 (DA — you)** |
|---|---|---|
| Route plan / per-DA cron schedule | **owns** | **consumes** |
| DA-internal pickup/delivery **sequencing** in a territory | — | **owns** (your priority queue) |
| Van→DA **deliver** handoff | owns driver side | **owns DA side** |
| DA→van **collect** handoff | owns van side | **owns DA side** |
| Parcel→loop binding | owns | feeds it the DA's accumulated first-mile parcels |
| Live van position / deviation | owns | **consumes** (re-check DA cron feasibility on `VAN_RUNNING_LATE`) |
| Cron-meeting feasibility (can the DA reach the van in time?) | provides the meeting times | **owns the hard check** |

> **The cron-meeting is a hard constraint** (project invariant): M5 must confirm a parcel can reach
> the hub cron *before* the airline cutoff — no DA assignment otherwise. M6 gives you the meeting
> times; M5 enforces feasibility against them.

---

## 7. ★ The M5 contract — what you NEED and what you GIVE

This is the section to keep open while designing M5. Sourced from `M5-DEPENDENCY-QUESTIONS.md`,
`M5-DISPATCH-DESIGN.md`, and the M3/M6 design docs.

<a id="kt-needs-m3"></a>
### 7.1 What M5 NEEDS from M3
| Need | Source | Status / caveat |
|---|---|---|
| **DA→hex territory map** at shift start | `GET /api/grid/{cityCode}/assignments?date=` → `{da_id, hex_ids[]}` (ACTIVE only) | ✅ exists. **Caveat (Q-M3-2):** the per-DA response may not include `n_das_on_tile`; M5 needs to know which hexes are *shared* (multi-DA) to decide round-robin vs single-DA dispatch. Likely needs a small addition (`nDasPerTile` map). |
| **Resolve GPS/address → hex** | `common.port.ServiceabilityPort` (in-process, preferred) or `GET /api/grid/serviceable-at?lat=&lon=` / `/api/grid/{cityCode}/tile-at` | ✅ exists. **Caveat (Q-M3-7):** confirm behaviour when GPS is *outside* the grid (DA on a motorway) — null vs 404 vs best-guess. M5 must fall back to last-known hex. |
| **`service_time_min` per hex** for cron feasibility | `h3_hex_demand_snapshot.serviceTimeMin` | ✅ field exists. **Caveat (Q-M3-5):** is there a read API/public service method, or does M5 read the repo directly (same monolith)? Decide with M3 owner. |
| **Live overload signal** for cross-territory dispatch | `GET /api/grid/{cityCode}/tiles/{hexId}/load-score` → `adjusted_load_score` | ⚠️ Fed by the queue-depth feed (see GIVES + ownership note below). M3's consumer is built; the **producer** is the open item. Confirm the response shape (Q-M3-3). |
| **Notification when territories change intraday** | M3's `GRID_EVENTS` exchange (`GridEventType`) | ⚠️ Confirm an assignment-updated routing key is emitted (Q-M3-6); else M5 polls `/assignments`. Without it, M5's in-memory map goes stale after a station-manager override. |

<a id="kt-needs-m6"></a>
### 7.2 What M5 NEEDS from M6
| Need | Source | Status |
|---|---|---|
| **Per-DA cron schedule** — vertex + day's meeting times + van | `DaCronScheduledEvent` on `EventStreams.CRON_EVENTS`; and/or `da_cron_schedule` table; and/or `GET /routing/cron/da/{daId}?date=` + `/next?at=` | ✅ **implemented** (event producer + table + REST). **Q-M6-1 / Q-M4-9 RESOLVED.** Remaining M5-side work (M6-D-008): M5 currently models *one* `scheduled_meeting_time`; the event/table carry a **`List<LocalTime>`** — M5 must store the list and pick the next. |
| **Live van lateness** to re-check feasibility | `VanRunningLateEvent` / `VanArrivedEvent` on `CRON_EVENTS` | ⚠️ Payload records **now exist** in `common.kafka.events.cron`; M6 *emission* of the run-time telemetry is still pending (van-driver app). Code to the contract now. |
| **Handoff completion / discrepancy** | `HandoffCompletedEvent` / `HandoffDiscrepancyEvent` on `CRON_EVENTS` | ✅ **now built.** M6's `HandoffService` reconciles a stop and `CronEventProducer.emitHandoff*` publishes these. M5 consumes them (close SLA / surface a discrepancy) — M5 does **not** reconcile. |

<a id="kt-gives"></a>
### 7.3 What M5 GIVES the rest of the system
| Output | Consumer | Notes |
|---|---|---|
| **DA assignment + pickup OTP flow** — assigns a booked shipment to a DA, drives `PICKUP_ASSIGNED → PICKED_UP` | M4 (state machine) | M4 already generates the OTP on `PICKUP_ASSIGNED` (`DaEventsConsumer`); M5 verifies via M4's internal endpoint (`/internal/v1/shipments/{ref}/pickup-otp/verify` — confirm {ref} vs {id}, Q-M4-3). |
| **DA-side events** on `EventStreams.DA_EVENTS` (`oneday.da.events`) | M4 (state transitions) | `DaEventType` **already exists** with the 8 M4-consumed values (`PICKUP_ASSIGNED`, `PICKUP_FAILED`, `VAN_HANDOFF_COMPLETED`, `DROP_ASSIGNED`, `DROP_COLLECTED`, `DROP_COMPLETED`, `DROP_FAILED`, `PICKUP_COMPLETED`), and `common.kafka.events.DaEvent(shipmentId, eventType)` is the **minimal consumer record** M4 reads. M5 adds the 5 *internal* values (`QUEUE_REORDERED`, `DA_ABSENT`, `CRON_MISSED`, `COD_COLLECTED`, `TASK_DEFERRED_SHIFT_ENDED`) and may produce a richer payload record (M4 ignores extra fields). Q-M4-6. |
| **DA→van collect handoff (DA side)** — first-mile parcel picked up, to be flown out | **M6 `DaFeedConsumer`** (binds it to a van loop) — and reconciles the van side at handoff | 🚨 **CONTRACT NOW CONCRETE & UNRESOLVED.** M6 binds queue `routing.da` to `DA_EVENTS` with **`#` (catch-all)** and reads every message as **`DaParcelPickedUpEvent(parcelId, cityId, daId, validDate, pickedUpAt)`** → `InboundParcel(COLLECT)` + `VanManifestService.bindCollect(...)`. Our `DaLifecycleEvent` is **shipment-level** (`shipmentId`/`occurredAt`, no `validDate`) → 3 fields deserialize **null** and corrupt M6's table. **Must resolve before M5 publishes to `DA_EVENTS`:** (a) parcel-vs-shipment id (with M8) + add `validDate`/`pickedUpAt`; (b) M6 narrows the `#` binding to the one collect key (else `DA_ABSENT`/`QUEUE_REORDERED`/etc. poison `routing.da`); (c) bind trigger = `PICKUP_COMPLETED`. `DaParcelPickedUpEvent` is flagged *PROVISIONAL — finalize with M5 owner* on the M6 side. |
| **Tile queue-depth** snapshot every ~5 min | M3 (computes load-score) | ⚠️ **Ownership now ambiguous — confirm before building.** M3's consumer `TileQueueDepthConsumer` is wired to `EventStreams.TILE_QUEUE_DEPTH = "orders.tile_queue_depth"` and its comment attributes the feed to **M4**, reading a `TileQueueDepthEvent(cityId, tileId, unservedOrders, bookedOrders, date)`. The M5 plan assumes **M5** publishes it. Cleanest path: M5 publishes that same `TileQueueDepthEvent` to the existing `TILE_QUEUE_DEPTH` exchange (M3's consumer is ready) rather than a new `dispatch.*` exchange. Decide M4-vs-M5 producer ownership with the M3/M4 owners. |
| **Cron-feasibility verdict** | the parcel's fate | The hard gate: a first-mile parcel only gets committed if its DA can hand it to the van on a loop that reaches the hub before the flight cutoff. |

### 7.4 What M5 NEEDS from M4 (you built M4 — sanity-check these)
- `ShipmentCreatedEvent.originTileId` — can M5 use it directly to skip the tile-at call? Always
  populated? (Q-M4-1)
- `ShipmentStateChangedEvent` for `HANDED_TO_DROP_VAN` — does it carry delivery coords + dropType,
  or must M5 GET the shipment? (Q-M4-2). And is an event emitted for **every** transition? (Q-M4-7)
- `ShipmentCancelledEvent` — does it carry the state at cancellation, so M5 knows whether a DA is
  already en route? (Q-M4-8)
- OD-8: is delivery verification **OTP or QR**? Changes M5's delivery endpoints materially. (Q-M4-5)

### 7.5 What M5 NEEDS from M1 (auth)
DA role name, city claim in JWT, station-manager city scope, the internal service-token mechanism
for M4 OTP calls, ADMIN role name. (Q-M1-1…5 — M1 is built, so verify against `docs/M1/`.)

---

> ↩ **[Back to the M5 Implementation Plan](M5-Implementation-Plan.md)** (end of the M5 contract).

## 8. Five things to internalise before you start M5

1. **Demand is minutes, weighted 70/30.** Every capacity/feasibility number in M3/M5/M6 is in
   order-engaged DA-minutes, not parcel counts. Your cron-feasibility math lives in this unit.
2. **The territory map is `da_hex_assignment` ACTIVE rows, loaded at shift start, patched on M3's
   `GRID_EVENTS` exchange.** That's M5's spatial source of truth.
3. **The cron-meeting is a hard constraint, and M6 hands you the meeting times as a *list* per DA.**
   M5 owns the feasibility check; store the list, not a single time.
4. **Plan-time M6 events are live; run-time ones are enum-defined but not yet emitted.** M6's
   *plan-time* producers — `DA_CRON_SCHEDULED`, `ROUTE_PLAN_PUBLISHED`, `ROUTE_CHANGED`,
   `SHUTTLE_SCHEDULED` — are wired (`CronEventProducer`). The *run-time* values (`VAN_RUNNING_LATE`,
   `HANDOFF_COMPLETED/DISCREPANCY`, `VAN_ARRIVED`, …) exist in `CronEventType` with typed payloads but
   are **not produced yet** (Part II, later PRs). Code M5's run-time integration to those contracts
   now; consume when they land. M6 Part II also relies on stub ports (`HubSortPort`, `DaAccumulationPort`).
5. **The tile queue-depth feed is two-way — and its producer ownership is unsettled.** M3's consumer
   is already built (`@RabbitListener` on `EventStreams.TILE_QUEUE_DEPTH = "orders.tile_queue_depth"`),
   but the code attributes the producer to M4 while the M5 plan assumes M5. Resolve this before
   wiring M5's publisher (see §7.3).

---

### Where everything lives
- M3 design (algorithm — read with the H3 caveat): `docs/M3/M3-GRID-DESIGN.md`,
  `docs/M3/M3-H3-REFACTOR-PLAN.md`, `docs/M3/M3-INTEGRATION.md`
- M6 design (read §11–§16 for the run-time half): `docs/M6/M6-ROUTING-DESIGN.md`
- M6 demo runbook: `docs/M6/M6-DEMO.md`, `docs/M6/M6-DEMO-FLOW.md`, `docs/M6/M6-INFEASIBLE-VERTICES.md`
- M5 (your module): `docs/M5/M5-DISPATCH-DESIGN.md`, `M5-DEPENDENCY-QUESTIONS.md`,
  `M5-SEQUENCES.md`, `M5-DA-STATUS-MACHINE.md`, `M5-ER-DIAGRAM.md`, `M5-Implementation-Plan.md`
- Event contracts across modules: `docs/EVENT-CONTRACTS.md`
- Code: `grid/src/main/java/com/oneday/grid/`, `routing/src/main/java/com/oneday/routing/`,
  shared contracts in `common/src/main/java/com/oneday/common/`
```
