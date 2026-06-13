# M6 Routing Demo — End-to-End Flow (with full request/response examples)

A rookie-friendly but technical walkthrough of how the M6 planning demo works: every API we hit, the
request/response shapes, the OSRM queries, and the OR-Tools model. Companion to `M6-DEMO.md`
(runbook) and `M6-DEMO-CARRYOVER.md` (what to merge back to `f-m6-design`).

---

## 1. The cast

| Player | Role | Where |
|---|---|---|
| **M3 (grid)** | Splits a city into **H3 hexagons**; assigns each delivery associate (DA) a **territory** | backend module `grid` |
| **M6 (routing)** | Plans **van routes** over **meeting points** where DAs hand parcels to vans | backend module `routing` |
| **OSRM** | Road-network engine. Two jobs: travel-time **matrix** (`/table`) and road **geometry** (`/route`) | external (Hetzner, full India) |
| **OR-Tools** | Google VRP solver. Given travel times + constraints, decides **which van visits which stop in what order** | Java lib inside `routing` |
| **UI** | React + Leaflet. Triggers the pipeline, draws the result | `demo-ui/` |

**Vocab:** *hex* = one H3 cell (~0.7 km²; Delhi ≈ 3,466). *territory* = a DA's hexes. *meeting
vertex* = a hex-corner point where a van meets DAs (M6 picks a minimal set). *loop* = one van round
trip hub → vertices → hub; a van repeats its loop `n_loops` times/day.

> **Naming gotcha:** the backend serializes **snake_case** (`da_ids`, not `daIds`). The UI fetch
> layer deep-converts responses snake→camel and writes request bodies snake_case explicitly.

---

## 2. Worked example — "Plan Delhi for tomorrow" (25 DAs, 13 vans)

Delhi `cityCode = delhi`, `cityId = f47ac10b-58cc-4372-a567-0e02b2c3d479`, `date = 2026-06-11`.

### Step 1 — Seed demand (M4 not built yet) — explicit & reproducible
```http
POST /api/demo/seed?cityCode=delhi&minMinutes=4&maxMinutes=10&date=2026-06-11
```
```json
{ "hexes_seeded": 3466, "min_minutes": 4.0, "max_minutes": 10.0, "seed_used": 8423190577… }
```
Writes one row per hex into `h3_hex_demand_snapshot` (each a random `minMinutes…maxMinutes` of work).
Pass an explicit `&seed=<long>` to make it **reproducible** (`new Random(seed)` → same surface every
time); omit it and the response's `seed_used` tells you the random seed that was rolled, so you can
replay it. **Seeding is now its own step** — it is no longer folded into territory generation, so the
demand surface isn't re-rolled on every replan.

> **Why decoupled:** Step 2 (territory gen) first calls `GET /api/demo/demand-count?cityCode=delhi&date=…`
> and **hard-errors ("seed demand first")** if the count is 0, instead of silently reseeding. So a city
> is seeded once, then territories/routes can be regenerated against that *same* demand as many times as
> you like — making plans comparable across runs.

### Step 2 — M3 builds DA territories
```http
POST /api/grid/delhi/replan
Content-Type: application/json

{ "da_ids": ["8792c6a7-204e-402e-ab43-efbbe8d014b7", "... 25 generated UUIDs ..."],
  "date": "2026-06-11" }
```
```json
{
  "id": "4a19f79d-ac67-4350-aa7d-cf9a947f197a",
  "status": "PROPOSED",
  "solver_type": "BALANCED_BFS",
  "adjacency_source": "GEOMETRIC_FALLBACK",
  "total_das": 25,
  "coverage_pct": 100.0,
  "understaffed_hex_ids": [],
  "regions": [
    { "da_id": "8caa87f7-527a-4fad-9560-2dc90eca490a",
      "n_das_required": 1, "estimated_demand_min": 1204.0,
      "hex_ids": ["...", "... 179 hexes ..."] },
    "... 24 more regions ..."
  ]
}
```
M3 grows 25 balanced contiguous territories. Still just a **proposal**.

### Step 3 — Approve, then activate (demo go-live shim)
```http
POST /api/proposals/4a19f79d-.../approve
{ "reviewer_id": "00000000-0000-0000-0000-000000000001" }
```
`→ 204 No Content` — assignments go `PROPOSED → APPROVED`.

M6 reads only **`ACTIVE`** assignments (production promotes `APPROVED → ACTIVE` via a day-of job that
doesn't exist yet), so the demo simulates it:
```http
POST /api/demo/activate?cityCode=delhi&date=2026-06-11
```
```json
{ "activated": 3466 }
```
> In the UI this is a **3-step flow**: **Seed demand** (with min/max/🎲-seed controls) → **Generate
> territories** (Steps 2–3: replan + approve + activate; refuses to run if demand-count is 0) →
> **Generate routes** (Steps 4–5). The standalone Approve button was removed (re-approving an APPROVED
> proposal 401s).

### Step 4 — Set the fleet
```http
PUT /routing/fleet/f47ac10b-58cc-4372-a567-0e02b2c3d479
{ "vans_available": 13, "capacity_packets": 1000, "cycle_time_max_minutes": 180 }
```
```json
{ "city_id": "f47ac10b-...", "vans_available": 13, "capacity_packets": 1000,
  "cycle_time_min_minutes": 120, "cycle_time_max_minutes": 180,
  "shuttle_cadence_minutes": 30, "max_da_to_vertex_minutes": 12, "dwell_minutes": 5,
  "updated_at": "2026-06-..." }
```
Updates one row in `city_fleet_config` (PUT is partial — null fields keep their stored value).

### Step 5 — M6 solves the routes (the heart)
```http
POST /routing/plans/f47ac10b-58cc-4372-a567-0e02b2c3d479/replan?date=2026-06-11
```
```json
{
  "plan_id": "d91ca524-ac6b-48f7-921a-2668cdc902a1",
  "city_id": "f47ac10b-...", "valid_for_date": "2026-06-11",
  "status": "PROPOSED", "source": "NIGHTLY", "solver_type": "OR_TOOLS", "revision": 1,
  "vans_used": 6, "recommended_van_count": 3, "provisioning_flag": "OK",
  "n_loops": 6, "realised_cycle_minutes": 114, "notes": null
}
```
See §3 for what happens **inside** this call. Then approve:
```http
POST /routing/plans/d91ca524-.../approve
{ "actor_id": "00000000-0000-0000-0000-000000000001" }
```
`→ 200`, `status: "APPROVED"`. This also writes `da_cron_schedule` (one row per DA: when/where to
meet its van) and fires a `DA_CRON_SCHEDULED` Kafka event.

### Step 6 — UI fetches what it needs to draw
```http
GET /routing/nodes/f47ac10b-...
```
```json
[ { "kind": "HUB",     "lat": 28.6139, "lon": 77.2090, "name": "Delhi Hub" },
  { "kind": "AIRPORT", "lat": 28.5562, "lon": 77.1000, "name": "Indira Gandhi Intl (DEL)" } ]
```
```http
GET /routing/plans/f47ac10b-.../stops?date=2026-06-11
```
```json
[ { "stop_id": "…", "van_id": "163fdf95-…", "loop_index": 0, "stop_seq": 1,
    "node_kind": "MEETING_VERTEX", "hex_vertex_id": "94f75b3b-…",
    "lat": 28.846, "lon": 77.2435,
    "planned_arrival": "07:36:52", "planned_departure": "07:41:52",
    "deliver_qty": 10, "collect_qty": 10, "load_after": 120 },
  "... 300 rows (≈ distinct vertices × n_loops) ..." ]
```
`/stops` is a demo endpoint that joins each stop's `hex_vertex_id` to the grid vertex's lat/lon
**server-side**, so the UI gets coordinates without downloading all ~7,000 city vertices.

---

## 3. Inside the M6 `replan` call (the pipeline)

`RoutePlanningServiceImpl.plan(cityId, date)` runs these stages:

1. **Read territories** — `GridDataAdapter.getDaTerritories` → M3 returns 25 ACTIVE territories
   (DA + hexes + each hex's corner vertices).
2. **Aggregate demand** — sum each territory's parcels; split **50/50** into deliver (last-mile) and
   collect (first-mile) — `DemandAggregationService`. **(See §3a for how seeded *minutes* become
   *packets*.)**
3. **Pick meeting points** — `GreedyMeetingPointSelectionService` runs a **set-cover**: the *fewest*
   hex-corner vertices such that every DA is within `max_da_to_vertex_minutes` (12) of one. 25 DAs
   collapsed to ~12 vertices. **This caps usable vans** — can't send 13 vans to 12 places.
4. **Build travel matrix** — coordinates of `{hub} ∪ {meeting vertices}` → **OSRM `/table`** (§4).
5. **Solve** — feed matrix + constraints to **OR-Tools** (§5) → ordered stops per van.
6. **Assemble + persist** — `RoutePlanAssembler` stamps ETAs (start 07:00 + travel + dwell), computes
   `n_loops`, writes `route_plan`, `route_plan_stop`, `da_cron_schedule`.

---

## 3a. Units: how seeded **minutes** become van **packets** (the kg≠mins confusion)

The demand snapshot stores a **service-minutes** number per hex (`demand_min` — "this hex needs ~6 min
of DA work"). But OR-Tools' Load/Delivered dimensions count **packets** (discrete parcels), and the
fleet's `capacity_packets` is in packets. Here is **every** conversion that bridges them — there is no
kg anywhere; "capacity" is a parcel count, not a weight:

| Step | Field / constant | Formula | Example (hex with `demand_min = 90`) |
|---|---|---|---|
| 1. Seed | `demand_min` per hex | random `minMinutes…maxMinutes` | `90` min |
| 2. Minutes → orders | `MIN_PER_ORDER = 15` | `orders = demand_min / 15.0` | `90 / 15 = 6` orders |
| 3. Order ≡ packet | (1:1) | `packets = orders` | `6` packets |
| 4. Sum over territory | — | Σ hex packets in the DA's hexes | e.g. `180` packets |
| 5. Split per vertex | first/last-mile **50/50** (Q3) | `deliverQty = collectQty = packets / 2` | `90` deliver + `90` collect |

So the chain is **minutes → (÷15) orders → (1:1) packets → split 50/50 deliver/collect**, and those
deliver/collect packet counts are what the Load dimension (§5d) must keep ≤ `capacity_packets`. A hex
"worth 90 minutes" is therefore **6 parcels**, not 90 of anything — `15 min/order` is the single knob
(`DemandAggregationService`) that sets how many parcels a minute of seeded demand implies.

> **Why the OR-Tools "recommended 62 / infeasible" happens:** if a *single* meeting vertex's
> `deliverQty` or `collectQty` exceeds `capacity_packets`, **no** number of vans can fit it on one van,
> so the model is infeasible at every fleet size. Because every hex is seeded, dense territories push
> per-vertex packets past `capacity_packets` (180 in the demo). The fix is to **raise capacity** or
> **lower seed demand** (fewer minutes → fewer packets) — not add vans. See `M6-DEMO-CARRYOVER.md` #2.

---

## 4. OSRM `/table` — the travel-time matrix (backend → OSRM)

M6 sends all node coordinates (lon,lat order!) and asks for pairwise **durations** in seconds.

```http
GET http://46.225.155.64:5000/table/v1/driving/77.2090,28.6139;77.2302,28.6439;77.2709,28.5680?annotations=duration
```
```json
{
  "code": "Ok",
  "durations": [
    [   0.0, 374.8, 710.4 ],
    [ 372.9,   0.0, 819.4 ],
    [ 656.7, 842.1,   0.0 ]
  ],
  "destinations": [ { "name": "Kartavya Path", "location": [77.209001, 28.61391] }, "..." ]
}
```
`durations[i][j]` = seconds to drive from node *i* to node *j* (node 0 = hub). Note it's **not
symmetric** (one-way roads). `RoutingOsrmClient.getTable` parses `durations` into a `double[][]`;
unreachable pairs get a ~28h penalty so the solver never routes through them. **These durations are
OSRM free-flow — no congestion factor is applied** (see §8).

---

## 5. The OR-Tools model (what constraints we add)

OR-Tools never sees a map — only the number matrix from §4 plus rules. From
`OrToolsVanRouteSolver.solve(matrix, vansAvailable, capacityPackets, cycleMaxMinutes)`:

```text
manager = RoutingIndexManager(nNodes, vansAvailable, depot = 0)   // node 0 = hub
model   = RoutingModel(manager)

# (a) ARC COST = travel seconds  → objective minimises total driving
travelCb = transit(i, j) -> matrix.travel(i, j)
model.setArcCostEvaluatorOfAllVehicles(travelCb)

# (b) TIME dimension: travel + per-stop service; each route's span ≤ cycleMax
timeCb = transit(i, j) -> matrix.travel(i, j) + node[j].serviceTimeSeconds   # dwell = 5 min/stop
model.addDimension(timeCb, slack=0, capacity = cycleMaxMinutes*60, fixStartToZero=true, "Time")
timeDim.setGlobalSpanCostCoefficient(100)   # ← BALANCE: penalise the longest route → spread work
                                            #   across vans instead of dumping all on the fewest

# (c) DELIVERED dimension: per-van end cumul = total parcels it delivers
deliveredCb = unary(i) -> node[i].deliverQty
model.addDimension(deliveredCb, 0, totalDeliveries, fixStartToZero=true, "Delivered")

# (d) LOAD dimension (VRPSPD peak load): net (collect − deliver) at each stop, bounded [0,capacity]
loadCb = unary(i) -> node[i].collectQty - node[i].deliverQty
model.addDimension(loadCb, 0, capacityPackets, fixStartToZero=false, "Load")

# (e) the van starts each loop loaded with exactly the deliveries it will drop:
for v in vans:  constrain( Load.cumul(start[v]) == Delivered.cumul(end[v]) )

# search
params.firstSolutionStrategy   = PATH_CHEAPEST_ARC
params.localSearchMetaheuristic = GUIDED_LOCAL_SEARCH
params.timeLimit                = 8s        # demo value (app yaml); was 45s
solution = model.solveWithParameters(params)
```

**Constraints in plain words:**
- Every meeting vertex is visited exactly once per loop.
- **Capacity** (d+e): the van's instantaneous load (start deliveries, then `−deliver +collect` at each
  stop) stays within `capacity_packets` everywhere — not just net.
- **Cycle time** (b): a loop can't exceed `cycle_time_max_minutes` (travel + 5-min dwell per stop).
- **Balance** (b, span coefficient): minimise the *longest* route → use more vans, shorter loops.
- **Objective**: minimise total travel + the makespan penalty.

Output per van: an ordered node sequence, e.g. `hub → v5 → v2 → v9 → hub`. The assembler then turns
that into timestamped stops and figures out how many loops fit the day.

`recommendVanCount` re-solves with `cycle_time_max` and rising van counts to report the *minimum*
fleet that holds the cycle target (this can read **lower** than `vans_used` now that balancing
spreads wider — they answer different questions).

---

## 6. OSRM `/route` — the road geometry (UI → OSRM)

OR-Tools gave only an **order**. To draw the curvy road line, the UI sends the ordered waypoints to
OSRM's *other* endpoint (`routingApi.js → osrmRoute`, via the Vite `/osrm` proxy):

```http
GET /osrm/route/v1/driving/77.2090,28.6139;77.2435,28.846;...;77.2090,28.6139?overview=full&geometries=geojson
```
```json
{
  "code": "Ok",
  "routes": [ {
    "distance": 130600.0,                         // metres, whole waypoint chain
    "duration": 9840.2,
    "geometry": { "type": "LineString",
      "coordinates": [ [77.2090,28.6131], [77.2101,28.6142], "... 2665 points ..." ] }
  } ],
  "waypoints": [ "..." ]
}
```
OSRM runs its own shortest-path search over the **pre-built OpenStreetMap road graph of India** and
returns the full road-following polyline. The UI flips `[lon,lat] → [lat,lon]` and draws a Leaflet
`Polyline` (straight-line fallback if OSRM is unreachable).

---

## 7. How distance & time are computed

- **Distance per van** — from `/route`'s `distance` field. The UI road-snaps **one representative
  loop** (`hub → vertices → hub`) → `perLoopDistanceKm`; whole-day = `perLoop × n_loops`.
- **ETAs per stop** — backend `RoutePlanAssembler`: start 07:00, add `matrix travel + 5-min dwell`
  stop by stop → `planned_arrival` / `planned_departure`. `realised_cycle_minutes` = one loop's span.
- **Why loops look identical** — a van visits the same vertices in the same order each loop; only the
  clock advances. Hence the UI draws **one marker per vertex** (numbered `1…n`), tooltip lists every
  loop's visit time, and a **loop selector** reads each loop's schedule.

---

## 8. Known realism gaps (read before trusting the km/time numbers)

The plan-time model is deliberately optimistic; these are tracked for calibration:

| Real-world cost | Modelled? | Where / how to fix |
|---|---|---|
| **Per-stop dwell** (DA handoff) | ✅ 5 min | `dwell_minutes` → added per stop in the Time dimension |
| **Traffic / congestion** | ❌ no | OSRM `/table` returns **free-flow** times. Apply a congestion factor (×1.4–1.8) to the matrix in `TravelMatrixService`, or use a traffic-aware OSRM profile |
| **Hub turnaround** (unload collected + load next loop's deliveries) | ❌ no | hub node `serviceTimeSeconds = 0`; `n_loops = window / loop_span` ignores between-loop time. Add a hub service time and include it when sizing `n_loops` |
| **Shift breaks / DA wait** | ❌ no | not modelled |

Consequence: realised km/day and loops/day are **upper bounds** — a real van will do fewer. See the
discussion in chat / `M6-DEMO-CARRYOVER.md` for the recommended fixes (congestion factor is the
single biggest lever).

---

## 9. One-line mental model

> **M3** carves the city into DA territories → **M6** picks meeting points, asks **OSRM `/table`** for
> travel times, hands them to **OR-Tools** which orders the stops + enforces capacity/cycle/balance,
> stamps ETAs, saves the plan → the **UI** asks **OSRM `/route`** to draw the road path and renders
> numbered loops with a hub pin.
