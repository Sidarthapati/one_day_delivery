# M3 — Serviceability & Grid Management: Design Doc

| Field | Value |
|-------|-------|
| Module | M3 — grid |
| Status | Draft v1.0 (supersedes v0.1 — implements full three-component algorithm from day one) |
| Author | Design session 2026-05-08, revised 2026-05-09 |
| Depends on | common, M4 (order history + GPS feed), OSRM (self-hosted road-time graph) |
| Consumed by | M4 (serviceability check), M5 (DA-to-tile map), M6 (grid vertices) |

---

## 1. What M3 Does 

M3 owns two distinct but related concerns:

1. **Serviceability**: given a (city, pincode) pair, answer whether we serve it — used by M4 at booking time.
2. **Grid management**: define the fixed 2 km × 2 km rectangular tile geometry, maintain the nightly DA-to-tile assignment, and expose tile/vertex data to M5 and M6.

It is the spatial foundation every other module stands on.

The nightly DA assignment algorithm uses three integrated components from day one:

| Component | What it replaces | Why |
|-----------|-----------------|-----|
| OSRM road-time graph | Geometric 4-connectivity (index adjacency) | Rivers, highways, and flyovers make geometrically adjacent tiles operationally unreachable — road travel time is the real constraint |
| Time-based demand scoring | Order-count demand score | Dense apartments and scattered bungalows with equal order counts take very different amounts of DA time — the real constraint is shift minutes, not parcel count |
| OR-Tools CP-SAT solver | Greedy BFS region growing | BFS makes locally greedy decisions that can never be undone; CP-SAT considers the full partition simultaneously and produces a provable near-optimal result |

---

## 2. Industry Context — How Other Companies Handle This

### 2.1 What major logistics players do

| Company | Approach | Notes |
|---------|----------|-------|
| **Amazon Logistics** | Irregular service areas ("routes") per driver, computed by routing solver nightly | No regular grid; each "route" is a TSP-optimized polygon. Dynamic ML demand forecasting, real-time intraday rebalancing. |
| **FedEx Ground** | Fixed service-area polygons per terminal, driver routes are daily TSP optimizations within polygon | Zones don't change; driver routes inside do. Closest to our model structurally. |
| **USPS** | "Carrier Routes" — fixed irregular polygons defined by historical mail volume | Very stable zones, rarely redrawn. Density drives route length, not zone size. |
| **Delhivery / Shadowfax (India)** | Pincode clusters assigned per hub, no spatial grid | Hub-and-spoke; serviceable pincodes drive assignment, not geometry. No formal partition optimization. |
| **Swiggy / Blinkit (India)** | Variable-size H3 hexagonal tiles; zone sizes shrink in high-density areas; real-time zone resizing | H3 gives better spatial uniformity; real-time reactivity is their key advantage over our nightly model. |
| **DoorDash** | Geohash-based rectangles; adjusts resolution per market density | Geohash is hierarchical; level 6 ≈ 1.2 km × 0.6 km in India — close to our 2 km tile. |

### 2.2 Honest comparison of our approach

**Where we are stronger than most Indian competitors (Delhivery, Shadowfax):**
- OSRM-backed adjacency means DA territories respect actual road network barriers — Delhivery's pincode clustering is blind to this.
- CP-SAT solver gives a provable near-optimal DA territory partition with an optimality bound. Most competitors use greedy heuristics or manual zone drawing.
- Human approval workflow prevents algorithmic errors from silently reaching ops.

**Where we are genuinely weaker than Swiggy/Blinkit and Amazon:**
- **No intraday rebalancing.** If demand spikes at 11am in a tile, the system cannot react until the next nightly run. This is a known limitation accepted for v1 — see §16 for the intraday roadmap.
- **No ML forecasting.** The 70/30 weighted average is simple and explainable, but systematically under-predicts spikes and over-predicts demand after holidays or events.
- **Fixed tile geometry.** High-density areas (Connaught Place) and sparse outskirts get the same 2 km tile. H3 variable resolution handles this better; our tile size makes it acceptable at 2 km but would be a real problem below 1 km.
- **Single travel-mode OSRM.** DAs on bikes vs tempos vs e-bikes have different effective tile sizes; we model them uniformly in v1.

### 2.3 Why fixed rectangular tiles over H3 / irregular polygons

Most companies at scale move to hexagonal or irregular polygons. For v1 we choose **fixed 2 km × 2 km rectangular tiles** for:

- **Operational legibility**: station managers can explain a tile to a DA using street names. An H3 hex or Voronoi cell is harder to communicate on the ground.
- **Simple adjacency math**: two tiles (r1,c1) and (r2,c2) share a grid edge iff `|r1−r2| + |c1−c2| == 1`. This check is used in CP-SAT contiguity validation and BFS fallback — no library dependency.
- **Index-based lookup**: any GPS coordinate maps to a tile via integer division — O(1), no spatial index needed for the common case.
- **Grid vertex alignment with van routes**: M6 uses tile corners as van stops. Rectangular tiles give a clean, regular graph.
- **Indian city structure**: major Indian metros have largely block-based street patterns in commercial cores, so rectangular tiles align reasonably with natural delivery zones.

The key trade-off: rectangular tiles are less spatially uniform than hex cells. At 2 km this error is acceptable; revisit H3 if sub-500m granularity is ever needed.

---

## 3. Tile Geometry

### 3.1 Fixed 2 km × 2 km tiles

Tiles are defined once per city and **never change**. The tile **geometry is static**. What changes nightly is **which DAs are assigned to which tiles** (the DA-to-tile assignment map).

This decouples "where is the serviceable area" (M3, static) from "how do we staff it" (M3 nightly job + M5 dispatch).

### 3.2 Lat/lon conversion

2 km in angular terms depends on the city's latitude. At latitude φ:

```
Δlat  = 2.0 / 111.32                        ≈ 0.01797°  (constant — 1° lat ≈ 111.32 km)
Δlon  = 2.0 / (111.32 × cos(φ × π / 180))               (varies with city)
```

Representative values for our five target cities:

| City | Approx. latitude | Δlat | Δlon |
|------|-----------------|------|------|
| Delhi | 28.6°N | 0.01797° | 0.02041° |
| Mumbai | 19.1°N | 0.01797° | 0.01901° |
| Bangalore | 12.9°N | 0.01797° | 0.01845° |
| Hyderabad | 17.4°N | 0.01797° | 0.01882° |
| Chennai | 13.1°N | 0.01797° | 0.01847° |

These values are computed once at city-grid initialization and stored as `tile_delta_lat` / `tile_delta_lon` in the `Grid` record.

### 3.3 Tile indexing

Given a city grid anchored at (origin_lat, origin_lon) (the south-west corner of the serviceable bounding box):

```
row = floor((lat  − origin_lat) / tile_delta_lat)
col = floor((lon  − origin_lon) / tile_delta_lon)
```

GPS → tile lookup is O(1): two subtractions and two integer divisions.

### 3.4 Active vs inactive tiles

Not all tiles in the bounding box are serviceable. A tile is **active** if at least one serviceable pincode's centroid falls within its bounds. Inactive tiles exist in the data model but are invisible to M4/M5/M6.

```
Active tile:   contains ≥ 1 serviceable pincode → assigned DAs, included in OSRM matrix, used in routing
Inactive tile: outside serviceable area → serviceability check returns false, excluded from solver
```

---

## 4. Serviceable Pincode Configuration

### 4.1 Config file

Each city has a YAML config file committed to the repository (or loaded from DB at startup):

```yaml
# config/serviceability/delhi.yaml
city_id: "city-delhi"
city_name: "Delhi"
center_lat: 28.6139
center_lon: 77.2090
serviceable_pincodes:
  - pincode: "110001"
    lat: 28.6448
    lon: 77.2167
    locality: "Connaught Place"
  - pincode: "110002"
    lat: 28.6394
    lon: 77.2253
    locality: "Darya Ganj"
  # ...
```

### 4.2 Pincode → tile mapping

At startup (and on admin re-initialization), M3 runs a one-time mapping job:

```
for each serviceable pincode P with centroid (lat, lon):
    tile = getTileAt(city_grid, lat, lon)
    upsert PincodeMapping(pincode=P, city_id, tile_id=tile.id, is_serviceable=true)
```

Stored in the DB for O(1) serviceability checks at booking time.

### 4.3 Serviceability check API

```
GET /grid/serviceability?city={city_id}&pincode={pincode}
→ { serviceable: true/false, tile_id: "..." }
```

Resolved entirely from the `PincodeMapping` table — no spatial computation at query time.

---

## 5. DA-to-Tile Assignment

### 5.1 Core rules

1. Each **active tile** must have **at least one DA assigned** to it at any time.
2. A DA can be assigned to **one or more tiles**, but those tiles must form a **contiguous (connected) region** — no island tiles.
3. A single tile can have **multiple DAs** assigned when its demand exceeds one DA's practical capacity.
4. The nightly assignment targets **~70% DA utilization** (PRD §6.2).
5. Assignment proposals are made by the system nightly and require **human approval** before taking effect.

### 5.2 Time-based demand score per tile

Each tile's demand is expressed in **estimated order-engaged DA minutes**, not order count. Order-engaged time covers both the service time at the customer location and the inter-stop travel time between consecutive pickups within the tile — the two components that directly consume a DA's shift under the 70% utilisation model.

```
service_time_per_order(tile)      = avg minutes spent at the customer location per pickup
                                  = mean(pickup_completed_at − arrived_at_pickup), last 7 days

inter_stop_travel_per_order(tile) = avg minutes travelling between consecutive pickups
                                    within this tile (same DA, same shift)
                                  = mean(arrived_at_pickup[n] − pickup_completed_at[n-1]),
                                    last 7 days

order_engaged_min_per_order(tile) = service_time_per_order(tile)
                                  + inter_stop_travel_per_order(tile)

demand_minutes(tile, date) =
    (0.70 × avg_daily_orders(tile, last_7_days) + 0.30 × orders_today(tile))
    × order_engaged_min_per_order(tile)
```

**Bootstrap mode (before M4 GPS data exists):**

Neither component is available for new tiles or in early operations. Bootstrap with city-wide flat defaults:

```
service_time_default       = 12 min/order   (time at customer location)
inter_stop_travel_default  =  5 min/order   (travel between consecutive stops — calibrate per city)
order_engaged_min_default  = 17 min/order   (combined; calibrate both components before go-live)
```

Mark the `tile_demand_snapshot` row with `is_bootstrapped = true` so station managers can see which tiles are running on default estimates. The bootstrapped default is intentionally conservative — better to over-assign DAs on day one than under-assign.

Thresholds for switching from bootstrap to real data (per component):
- `service_time_per_order`: once ≥ 20 completed pickups exist for the tile, switch to the real per-tile average.
- `inter_stop_travel_per_order`: once ≥ 20 consecutive stop pairs exist for the tile, switch to the real per-tile average.
- Clear `is_bootstrapped` only when **both** components have crossed their respective thresholds.
- Below either threshold, use the city-wide average for that component (not the flat global default). Both city-wide averages are recalculated nightly from all tiles with real data.

**Inter-stop travel reliability guards:**

Two conditions can make a tile's `inter_stop_travel_per_order` measurement unreliable even after the lifetime threshold is crossed:

1. **Sparse tiles — too few pairs in the current 7-day window.** A tile with 2 orders/day yields ~0.5 consecutive same-tile stop pairs per day (the DA rarely routes both orders back-to-back). In any 7-day rolling window this gives ≈ 3 pairs — statistically meaningless and easily dominated by a single anomalous day. Guard: if the 7-day window contains fewer than `MIN_INTER_STOP_PAIRS_PER_WINDOW` pairs (default: 5), fall back to the city-wide `inter_stop_travel` average for that nightly calculation regardless of lifetime pair count.

2. **Outlier detours — single high measurement inflating the average.** A traffic incident, missed turn, or DA stopping briefly for another reason between two same-tile pickups can produce a single pair with 15–20 min travel, far beyond what intra-tile routing should take. Guard: before averaging, winsorise each individual pair at `tile_traversal_cap_sec` — the OSRM road time from the tile's south-west corner to its north-east corner (the longest plausible intra-tile road trip). Pre-computed per tile at grid initialisation and stored in the `tile` table (§6.2). For a 2 km × 2 km tile this is typically 6–10 min at Indian city traffic speeds; any pair exceeding this is clamped to the cap before entering the average.

These two guards together ensure that sparse tiles and anomalous measurements fall back to the city-wide average rather than producing inflated demand estimates that would over-assign DAs.

### 5.3 DA capacity (time-based)

```
shift_minutes    = configured shift duration in minutes (e.g., 480 min = 8 hours)

// Utilisation definition:
//   70% of shift = order-engaged time: travel to each pickup + service at each pickup
//   30% of shift = unproductive time:  hub wait, idle repositioning, no-order gaps
//
// demand_minutes is already expressed in order-engaged minutes (service + inter-stop travel),
// so DA capacity uses the same unit — no further conversion factor is needed.

DA_target_load   = shift_minutes × 0.70   = 480 × 0.70 = 336 min   (target utilisation)
DA_max_load      = shift_minutes × 0.90   = 480 × 0.90 = 432 min   (hard ceiling)
```

`DA_target_load` and `DA_max_load` are per-city ops configuration values; calibrate from GPS utilisation data after the first two weeks of operations. The 70% target and 90% hard ceiling match the PRD utilisation requirement. Note that `DA_max_load` is an overflow ceiling for demand spikes — the operating expectation is that most DAs finish near the 336-minute target, not at 432.

In the CP-SAT model, demand and capacity are both in order-engaged minutes. This makes the load-balance constraint physically meaningful.

### 5.4 Assignment algorithm — three-component design

The nightly assignment algorithm integrates three components. This section describes each component and how they compose.

---

#### Component A — OSRM road-time graph

**Purpose:** Replace geometric tile adjacency with road-travel-time adjacency.

**Why this matters:** In Indian cities, two geometrically adjacent tiles (index-adjacent) may be separated by a river, railway line, or flyover requiring a 20+ minute detour. If BFS or CP-SAT treat them as neighbors, a DA gets a "contiguous" territory that is physically uncoverable in a shift.

**Implementation:**

OSRM (Open Source Routing Machine) is a self-hosted, open-source routing engine that runs on OpenStreetMap data. It exposes a `/table` endpoint that computes an N×N travel-time matrix in a single HTTP call.

At grid initialization, M3 precomputes a **tile-centroid travel-time matrix** for all active tiles in the city:

```
centroid(tile) = (
    grid.origin_lat + (tile.row_idx + 0.5) × tile_delta_lat,
    grid.origin_lon + (tile.col_idx + 0.5) × tile_delta_lon
)

OSRM /table request: sources = [centroid(t) for all active tiles]
                     destinations = same list
Response: travel_time_seconds[i][j] = road travel time from tile i centroid to tile j centroid
```

Two tiles are **road-reachable neighbors** iff:

```
travel_time_seconds[i][j] <= ADJACENCY_THRESHOLD_SECONDS  (default: 600 = 10 minutes)
```

The resulting road-adjacency graph replaces the geometric 4-connectivity graph everywhere in M3.

**Storage:** The matrix is stored in `tile_travel_time` (see §6.5). It is precomputed at initialization and refreshed monthly (or on admin trigger if major road network changes occur).

**OSRM hosting:** Self-hosted on a city-specific OSM extract. India OSM data is downloaded from Geofabrik and updated monthly. One OSRM instance per city is overkill — a single instance with city-specific routing profiles is sufficient. See §13.5 for hosting details.

**Fallback:** If OSRM is unavailable at replan time, fall back to the geometric 4-connectivity graph and flag the proposal with `adjacency_source = GEOMETRIC_FALLBACK`. Station managers are alerted so they can manually inspect territory proposals across known barriers.

---

#### Component B — CP-SAT territory partition solver

**Purpose:** Produce a globally near-optimal, provably balanced partition of tiles into DA territories.

**Why CP-SAT over greedy BFS:**
- BFS assigns tiles greedily and never reconsiders. Early decisions constrain all later ones — by the 40th DA territory, the remaining tiles are whatever was left over, with no recourse.
- CP-SAT models the full partition simultaneously. It finds the globally best assignment or provides a certified bound (e.g., "this solution is within 3% of optimal").
- At our scale (50–200 active tiles, 20–70 DAs), CP-SAT solves in under 10 seconds — easily within the nightly batch window.

**CP-SAT model formulation:**

*Variables:*
```
assignment[i] ∈ {0, 1, ..., K-1}    for each active tile i, which DA k is assigned to it
```

*Load-balance constraints (one per DA):*
```
DA_min_load ≤ Σ(demand_minutes[i] for i where assignment[i] == k) ≤ DA_max_load

where:
  DA_min_load = DA_target_load × (1 - LOAD_TOLERANCE)   (default tolerance: 0.30)
  DA_max_load = DA_max_load from §5.3
```

*Symmetry-breaking constraint (speeds up solver):*
```
assignment[seed_tile_k] == k    for each DA k, where seed_tile_k is the highest-demand
                                tile not yet assigned as a seed for a lower-indexed DA
```

*Objective:*
```
minimize: max_load − min_load
where max_load = max over all DAs k of Σ demand_minutes[i] assigned to k
      min_load = min over all DAs k of Σ demand_minutes[i] assigned to k
```

This minimizes the worst-case imbalance across all DAs.

**Contiguity enforcement — lazy cuts approach:**

Contiguity ("a DA's tiles must form a connected subgraph") cannot be expressed as a single linear constraint in CP-SAT. We use an iterative lazy-cuts procedure:

```
Round 0: Solve CP-SAT with only load-balance constraints (no contiguity).

For each round r = 0, 1, 2, ...:
    solution = current CP-SAT solution

    For each DA k:
        S_k = {tiles i : assignment[i] == k}
        Run BFS on the road-adjacency graph restricted to S_k.
        components = connected components of S_k.

        If |components| == 1:
            territory k is connected → no cut needed.
        Else:
            Let C_main = largest component by total demand_minutes.
            For each other component C_j (smaller components):
                Add constraint: NOT all(assignment[i] == k for i in C_j)
                // This forces at least one tile in C_j to move out of territory k,
                // breaking the disconnected assignment.

    If no cuts were added in this round → all territories are connected → STOP.
    Else → re-solve with added cuts.
```

**Convergence:** At our scale (≤200 tiles, ≤70 DAs), lazy cuts converge in 2–5 rounds. Each round adds at most K cuts (one per disconnected territory). Total solver time including cuts: under 30 seconds in the worst case.

**Worked example — lazy cuts on a small grid:**

Imagine a city with 6 tiles in a 2×3 grid and 2 DAs. Each tile has equal demand (1 unit). Each DA needs exactly 3 tiles, and those 3 tiles must form a connected block.

```
[1][2][3]
[4][5][6]

Road adjacency edges: 1↔2, 2↔3, 1↔4, 2↔5, 3↔6, 4↔5, 5↔6
```

*Round 0 — solve with load-balance only (no connectivity rule):*

The solver finds a balanced split:
- DA 1: tiles {1, 3, 5}
- DA 2: tiles {2, 4, 6}

Both groups have 3 tiles (load balanced). Now check connectivity.

*BFS check for DA 1 ({1, 3, 5}):*
Starting at tile 1: can we reach 3? Only via 2, but 2 belongs to DA 2 — blocked.
Can we reach 5? Only via 2 or 4, both in DA 2 — blocked.
Result: three isolated islands. Not valid.

*Add cut:* "It is NOT allowed for tiles {1, 3, 5} to all be assigned to DA 1 simultaneously."

*Round 1 — re-solve with the cut:*

The solver can no longer use {1,3,5} for DA 1. It finds:
- DA 1: tiles {1, 2, 3}
- DA 2: tiles {4, 5, 6}

*BFS check for DA 1 ({1, 2, 3}):* 1↔2↔3 — all reachable. ✓
*BFS check for DA 2 ({4, 5, 6}):* 4↔5↔6 — all reachable. ✓

No cuts needed. Done in 2 rounds.

The key insight: encoding "connected" directly as a constraint requires dozens of extra variables and makes the model much harder to solve. Lazy cuts instead lets the solver find a good balanced answer first, then patches only the connectivity violations that actually appear — which in practice is a small fraction of territories.

**Fallback to BFS:** If the CP-SAT solver fails (timeout > 60 seconds, OR-Tools not available, or unresolvable model), fall back to BFS region growing (the algorithm from the original v0.1 design doc). Log the fallback and flag the proposal with `solver = BFS_FALLBACK`. The station manager sees the flag during review.

---

#### Component C — Multi-DA tile pre-processing

Before passing the tile graph to the CP-SAT solver, tiles with very high demand are split into virtual sub-tiles. This prevents the solver from having to assign the same physical tile to multiple DAs (which would require intra-tile routing, not M3's concern).

```
For each active tile T where demand_minutes[T] > DA_max_load:
    n_virtual = ceil(demand_minutes[T] / DA_target_load)
    Create n_virtual virtual tiles V_1 ... V_n, each with:
        demand_minutes = demand_minutes[T] / n_virtual
        adjacency = same as T (all virtual sub-tiles are mutually adjacent to each other and to T's real neighbors)
    Replace T with {V_1 ... V_n} in the solver input.
    Record the split in the proposal output so M5 knows T has multiple DAs.
```

After solving, all virtual sub-tiles assigned to the same DA are collapsed back to a single record in the `da_tile_assignment` table with `n_das_on_tile = n_virtual`. M5 uses `n_das_on_tile > 1` to split the intra-tile dispatch queue.

---

#### Full algorithm flow (nightly)

```
Step 0: Pre-computation
─────────────────────────────────────────────────────────────────
a. Load active tiles for city.
b. Compute demand_minutes for each tile (Component B input, §5.2).
c. Load road-adjacency graph from tile_travel_time (Component A output).
   This is a DB read — OSRM is not called at replan time. The matrix was
   precomputed and stored during the last OSRM refresh (§9). If the stored
   matrix is absent or stale (> 45 days old), fall back to geometric
   4-connectivity with GEOMETRIC_FALLBACK flag. In steady-state this fallback
   should never fire; it guards against a brand-new city before its first OSRM
   refresh, or a failed monthly refresh.
d. Pre-process multi-DA tiles (Component C).
e. K_available = count of active DAs for city for tomorrow.
   K_needed    = ceil(Σ demand_minutes over all tiles / DA_target_load)
   If K_available < K_needed → flag as understaffed; continue (solver assigns as best it can).

Step 1: CP-SAT solve
─────────────────────────────────────────────────────────────────
a. Build CP-SAT model (§5.4 Component B formulation).
b. Set solver time limit: 60 seconds.
c. Solve round 0 (load-balance only).
d. Run lazy-cuts loop until all territories are connected (§5.4 Component B contiguity).
e. Extract solution: assignment[i] → DA mapping.
f. Record optimality_gap from solver (e.g., "solution is within 2% of optimal").
g. If solver times out or fails → fall back to BFS, set BFS_FALLBACK flag.

Step 2: Post-processing
─────────────────────────────────────────────────────────────────
a. Collapse virtual sub-tiles back to physical tiles.
b. Identify low-utilization territories (total_demand < DA_target_load × 0.30):
   Flag these for station manager attention — sparse coverage, DA will have low utilization.
c. Compute per-region statistics:
   - estimated_demand_minutes
   - estimated_utilization_pct = demand_minutes / (DA_daily_capacity × n_das)
   - contiguity_verified = true (always at this point, by construction)

Step 3: Output proposal (identical schema to v0.1)
─────────────────────────────────────────────────────────────────
AssignmentProposal {
    city_id,
    valid_for_date,
    solver_type:          CP_SAT | BFS_FALLBACK,
    adjacency_source:     OSRM | GEOMETRIC_FALLBACK,
    optimality_gap_pct:   double | null (null for BFS_FALLBACK),
    regions: [
        {
            region_id,
            tile_ids:             [...],
            n_das_required:       int,
            estimated_demand_min: double,
            estimated_util_pct:   double,
            has_bootstrapped_tiles: bool,   // true if any tile used bootstrap defaults for either demand component
        }
    ],
    total_das_required,
    understaffed_tiles:   [...],
    status:               PROPOSED
}
```

The station manager UI shows `solver_type`, `adjacency_source`, and `optimality_gap_pct` as metadata on the proposal so reviewers understand what they're approving.

### 5.5 Adjacency invariant enforcement

When a station manager manually overrides a tile assignment (moves a tile from one DA to another), M3 validates contiguity on both the source and destination territories:

```
validateConnected(tile_set):
    Build subgraph of tile_set using road-adjacency graph (§6.5).
    Run BFS from any tile.
    If all tiles reachable → connected (valid).
    Else → reject: "DA territory must be contiguous. Disconnected tiles: [...]"
```

Manual overrides use the same road-adjacency graph as the solver — not the geometric graph. This ensures overrides don't introduce road-barrier violations.

### 5.6 Bootstrap mode (early operations)

In the first weeks of operation, M4 has no GPS data for either demand component. The system operates with flat defaults for all tiles:

```
service_time_default       = 12 min/order
inter_stop_travel_default  =  5 min/order
order_engaged_min_default  = 17 min/order   (combined)
```

The station manager proposal UI shows a banner: **"X of Y tiles are using estimated engagement durations. Territory sizing will improve as delivery data accumulates."**

After 14 operating days:
- Tiles with ≥ 20 completed pickups switch to real per-tile `service_time_per_order`.
- Tiles with ≥ 20 consecutive stop pairs switch to real per-tile `inter_stop_travel_per_order`.
- `is_bootstrapped` is cleared only when **both** components have real data.
- Tiles below the threshold for either component use the city-wide average for that component (not the flat global default). Both averages are recalculated nightly from all tiles with real data.

---

## 6. Data Model

### 6.1 Grid

```sql
CREATE TABLE grid (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id        UUID NOT NULL,
    origin_lat     DOUBLE PRECISION NOT NULL,
    origin_lon     DOUBLE PRECISION NOT NULL,
    tile_delta_lat DOUBLE PRECISION NOT NULL,
    tile_delta_lon DOUBLE PRECISION NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(city_id)
);
```

One row per city. Never updated after creation.

### 6.2 Tile

```sql
CREATE TABLE tile (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grid_id             UUID NOT NULL REFERENCES grid(id),
    row_idx             INT NOT NULL,
    col_idx             INT NOT NULL,
    is_active           BOOLEAN NOT NULL DEFAULT false,
    traversal_cap_sec   INT,   -- OSRM road time SW corner → NE corner; winsorisation ceiling for inter-stop travel
    UNIQUE(grid_id, row_idx, col_idx)
);
```

`traversal_cap_sec` is computed once at grid initialisation via two OSRM point lookups per tile (SW and NE corners). Null until the OSRM matrix refresh completes; the nightly job skips winsorisation for tiles with a null cap and uses the city-wide average instead.

Geometry is fully derived from grid + (row_idx, col_idx). No PostGIS column needed.

### 6.3 Tile demand snapshot

```sql
CREATE TABLE tile_demand_snapshot (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tile_id               UUID NOT NULL REFERENCES tile(id),
    snapshot_date         DATE NOT NULL,
    hist_avg_orders       DOUBLE PRECISION NOT NULL,   -- 7-day rolling average order count
    current_orders        INT NOT NULL,                -- orders today at snapshot time
    demand_score_orders   DOUBLE PRECISION NOT NULL,   -- 0.7*hist + 0.3*current (order-count)
    service_time_min          DOUBLE PRECISION NOT NULL,   -- avg minutes at customer location per pickup
    inter_stop_travel_min     DOUBLE PRECISION NOT NULL,   -- avg minutes travelling between consecutive pickups
    order_engaged_min         DOUBLE PRECISION NOT NULL,   -- service_time_min + inter_stop_travel_min
    demand_score_minutes      DOUBLE PRECISION NOT NULL,   -- demand_score_orders × order_engaged_min
    is_bootstrapped           BOOLEAN NOT NULL DEFAULT false, -- true = either component uses bootstrap default
    UNIQUE(tile_id, snapshot_date)
);
```

`demand_score_minutes` is the value passed to the CP-SAT solver. `demand_score_orders` is retained for station manager reporting and audit.

### 6.4 Pincode mapping

```sql
CREATE TABLE pincode_mapping (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id        UUID NOT NULL,
    pincode        VARCHAR(10) NOT NULL,
    tile_id        UUID REFERENCES tile(id),
    is_serviceable BOOLEAN NOT NULL DEFAULT true,
    UNIQUE(city_id, pincode)
);
```

### 6.5 Tile travel time (OSRM road-adjacency matrix)

```sql
CREATE TABLE tile_travel_time (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grid_id             UUID NOT NULL REFERENCES grid(id),
    from_tile_id        UUID NOT NULL REFERENCES tile(id),
    to_tile_id          UUID NOT NULL REFERENCES tile(id),
    travel_time_seconds INT NOT NULL,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(grid_id, from_tile_id, to_tile_id)
);
```

Only pairs where `travel_time_seconds <= ADJACENCY_THRESHOLD_SECONDS` (default 600) are stored. The adjacency graph in Java is built by querying this table at replan time and loading it into a `Map<UUID, List<UUID>>`.

This table is recomputed on admin trigger (monthly, or after major road network changes). Old rows are deleted and replaced — this is the only M3 table that is not append-only, because travel times are authoritative physical reality, not a business decision that requires an audit trail.

### 6.6 DA tile assignment

```sql
CREATE TABLE da_tile_assignment (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id    UUID NOT NULL REFERENCES assignment_proposal(id),  -- which proposal this row belongs to
    da_id          UUID NOT NULL,
    tile_id        UUID NOT NULL REFERENCES tile(id),
    valid_date     DATE NOT NULL,
    n_das_on_tile  INT NOT NULL DEFAULT 1,
    status         VARCHAR(20) NOT NULL,   -- PROPOSED | ACTIVE | SUPERSEDED
    proposed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by    UUID,
    approved_at    TIMESTAMPTZ,
    UNIQUE(proposal_id, da_id, tile_id, valid_date)
);
```

Rows are never deleted or physically mutated (da_id, tile_id, proposal_id, valid_date are immutable). The `status` field IS updated as rows advance through their lifecycle — this is tracking lifecycle state, not rewriting history. Multiple proposals for the same (da, tile, date) are valid and expected; the `proposal_id` in the constraint is what makes them distinct.

**V9 migration ships `UNIQUE(da_id, tile_id, valid_date)` — this must be replaced by a V10 migration** before Phase 5. The original constraint blocks intraday override proposals: writing a new PROPOSED row for (DA-A, T1, today) under proposal P2 would collide with the existing ACTIVE row for (DA-A, T1, today) under P1. With `proposal_id` included the two rows are distinct.

### 6.7 Assignment proposal

```sql
CREATE TABLE assignment_proposal (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id               UUID NOT NULL,
    valid_for_date        DATE NOT NULL,
    status                VARCHAR(20) NOT NULL,  -- PROPOSED | APPROVED | REJECTED | SUPERSEDED
    proposal_type         VARCHAR(30) NOT NULL DEFAULT 'NIGHTLY',  -- NIGHTLY | INTRADAY_OVERRIDE | INTRADAY_SUGGESTION | INTRADAY_SHARE
    solver_type           VARCHAR(30) NOT NULL,  -- CP_SAT | BFS_FALLBACK
    adjacency_source      VARCHAR(30) NOT NULL,  -- OSRM | GEOMETRIC_FALLBACK
    optimality_gap_pct    DOUBLE PRECISION,      -- null for BFS_FALLBACK
    total_das             INT NOT NULL,
    coverage_pct          DOUBLE PRECISION,
    understaffed_tile_ids JSONB,                 -- array of tile UUIDs where K_available < K_needed
    proposed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by           UUID,
    reviewed_at           TIMESTAMPTZ,
    notes                 TEXT
);
```

### 6.8 Assignment proposal region

Per-region detail for a proposal. One row per DA per proposal. Together with `da_tile_assignment` (filtered by `proposal_id` and `da_id`), this provides the full regions array shown in §5.4 Step 3.

```sql
CREATE TABLE assignment_proposal_region (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id            UUID NOT NULL REFERENCES assignment_proposal(id),
    da_id                  UUID NOT NULL,
    n_das_required         INT NOT NULL DEFAULT 1,        -- > 1 for multi-DA tiles (Component C)
    estimated_demand_min   DOUBLE PRECISION NOT NULL,     -- total demand_minutes for this territory
    estimated_util_pct     DOUBLE PRECISION NOT NULL,     -- estimated_demand_min / (DA_target_load × n_das_required)
    has_bootstrapped_tiles BOOLEAN NOT NULL DEFAULT false, -- true if any tile in territory used bootstrap defaults
    UNIQUE(proposal_id, da_id)
);
```

Tile IDs for a region are read from `da_tile_assignment WHERE proposal_id = ? AND da_id = ?`. Append-only.

### 6.9 Grid vertex

```sql
CREATE TABLE grid_vertex (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grid_id  UUID NOT NULL REFERENCES grid(id),
    row_idx  INT NOT NULL,
    col_idx  INT NOT NULL,
    lat      DOUBLE PRECISION NOT NULL,
    lon      DOUBLE PRECISION NOT NULL,
    UNIQUE(grid_id, row_idx, col_idx)
);
```

Vertices are pre-computed for M6. A grid with M rows × N columns of tiles has (M+1) × (N+1) vertices.

---

## 7. API Surface

### 7.1 Serviceability check (consumed by M4)

```
GET /grid/serviceability
Query: city_id, pincode
Response: { serviceable: bool, tile_id: string | null }
```

Simple DB lookup on `pincode_mapping`. No computation at query time.

### 7.2 Tile for GPS coordinate (consumed by M5)

```
GET /grid/tile-at
Query: city_id, lat, lon
Response: { tile_id: string, row: int, col: int }
```

Pure arithmetic — O(1), no DB hit if grid config is cached in memory.

### 7.3 DA assignments for a date (consumed by M5)

```
GET /grid/assignments
Query: city_id, date
Response: [{ da_id, tile_ids: [...], tile_count: int }]
```

### 7.4 Tiles for a DA (consumed by M5)

```
GET /grid/assignments/da/{da_id}
Query: date
Response: { da_id, tile_ids: [...], n_das_per_tile: { tile_id: int } }
```

### 7.5 Grid vertices (consumed by M6)

```
GET /grid/vertices
Query: city_id
Response: [{ vertex_id, row, col, lat, lon }]
```

### 7.6 Assignment proposal operations (station manager UI)

```
GET  /grid/proposals?city_id={}&date={}
GET  /grid/proposals/{proposal_id}
POST /grid/proposals/{proposal_id}/approve
POST /grid/proposals/{proposal_id}/reject
PUT  /grid/proposals/{proposal_id}/regions/{region_id}/override
POST /grid/assignments/intraday-override
POST /grid/assignments/tile-share
POST /grid/proposals/{proposal_id}/approve-intraday
```

The `GET /{proposal_id}` response includes `solver_type`, `adjacency_source`, `optimality_gap_pct`, and per-region `has_bootstrapped_tiles` so reviewers see metadata about the quality of the proposal they're approving.

**Tile-share endpoint** (`POST /grid/assignments/tile-share`): adds a DA to a tile without removing the existing DA. The body is `{ city_id, da_id, tile_id, requested_by }`. The system validates (a) the DA is not already assigned to this tile and (b) the tile is road-adjacent to at least one tile already in the DA's territory. Creates an `INTRADAY_SHARE` proposal pending approval. Response returns a `TileShareResponse` with the new `proposal_id` for the approval screen.

Unlike `intraday-override` (Scenario B), tile-share does not supersede the existing DA's assignment — both DAs end up with `n_das_on_tile = 2` for that tile.

### 7.7 No-DA alert (Kafka, produced by M3, consumed by M10)

Topic: `grid.no_da_alert`

```json
{
  "event_type": "grid.no_da_alert",
  "city_id": "...",
  "tile_id": "...",
  "valid_date": "2026-05-09",
  "alert_reason": "NO_ASSIGNMENT | DA_ABSENT",
  "emitted_at": "..."
}
```

### 7.8 Tile load score (consumed by M5 for cross-territory dispatch decisions)

```
GET /grid/tiles/{tile_id}/load-score
Response: {
    tile_id:              string,
    operating_date:       date,
    expected_orders:      double,       -- demand_score_orders from nightly snapshot
    unserved_orders:      int,          -- live count from dispatch.tile_queue_depth
    load_score:           double,       -- unserved_orders / expected_orders (1.0 = on plan)
    shift_elapsed_pct:    double,       -- fraction of shift elapsed (0.0–1.0)
    adjusted_load_score:  double        -- load_score / shift_elapsed_pct (pace-adjusted)
}
```

`adjusted_load_score` normalises for time of day: a tile with 80% of expected orders unserved at 20% through the shift is in much worse shape than the same count at 80% through. A score > 1.5 means the tile is overloaded relative to pace. M5 uses this to decide when cross-territory dispatch is warranted (see §16.4).

The response is computed from in-memory state (no DB hit at query time). M3 maintains a per-tile unserved-order counter updated by consuming `dispatch.tile_queue_depth`.

### 7.9 Tile overload alert (Kafka, produced by M3, consumed by station manager notification service)

Topic: `grid.tile_overload_alert`

```json
{
  "event_type":          "grid.tile_overload_alert",
  "city_id":             "...",
  "tile_id":             "...",
  "da_id":               "...",
  "operating_date":      "2026-05-09",
  "alert_severity":      "WARNING | CRITICAL",
  "expected_orders":     12.4,
  "unserved_orders":     19,
  "adjusted_load_score": 1.87,
  "sustained_minutes":   18,
  "emitted_at":          "..."
}
```

`alert_severity`:
- `WARNING`: `adjusted_load_score >= 1.5`, sustained for ≥ 15 minutes
- `CRITICAL`: `adjusted_load_score >= 2.0`, sustained for ≥ 10 minutes, or `unserved_orders > DA_daily_capacity` at any point

Both severity levels notify the station manager via push notification. `CRITICAL` also sends an SMS fallback if the push is not acknowledged within 5 minutes.

### 7.10 Assignment updated event (Kafka, produced by M3, consumed by M5)

Topic: `grid.assignment_updated`

Published by `ProposalServiceImpl` whenever an intraday proposal (INTRADAY_OVERRIDE or INTRADAY_SHARE) is approved and activated.

```json
{
  "event_type":      "grid.assignment_updated",
  "city_id":         "...",
  "operating_date":  "2026-05-17",
  "proposal_type":   "INTRADAY_OVERRIDE",
  "affected_da_ids": ["da-uuid-A", "da-uuid-B"],
  "approved_at":     "2026-05-17T10:15:00+05:30"
}
```

M5 subscribes to this topic and re-queries `da_tile_assignment WHERE da_id IN (affected_da_ids) AND valid_date = operating_date AND status = 'ACTIVE'` to patch its in-memory assignment cache for only the affected DAs. A full cache flush is not needed — all other DAs are unchanged.

Not emitted for nightly proposal approvals (M5 loads the full assignment map fresh at shift start).

---

## 8. Nightly Replan Job

The nightly batch job runs after midnight before the first shift. It is a Spring `@Scheduled` task in the `grid` module.

```
Nightly Grid Replan (runs at 01:00 local city time):

1. Fetch order history for past 7 days from M4 (DB query on shipments table,
   reading pickup tile_id + created_at only).

2. Fetch today's order count per tile (live from M4 Kafka snapshot).

3. Fetch per-tile demand components from M4 GPS data:

   -- Service time: minutes spent at customer location per pickup
   SELECT tile_id,
          AVG(EXTRACT(EPOCH FROM (pickup_completed_at - arrived_at_pickup))/60) AS service_time_min
   FROM shipment_leg_events
   WHERE event_type IN ('ARRIVED_AT_PICKUP', 'PICKUP_COMPLETED')
     AND created_at >= now() - interval '7 days'
   GROUP BY tile_id;

   -- Inter-stop travel: minutes between consecutive pickups by the same DA in the same tile on the same shift
   SELECT e2.tile_id,
          AVG(EXTRACT(EPOCH FROM (e2.arrived_at - e1.pickup_completed_at))/60) AS inter_stop_travel_min
   FROM shipment_leg_events e1
   JOIN shipment_leg_events e2
       ON e1.da_id        = e2.da_id
      AND e1.shift_date   = e2.shift_date
      AND e1.tile_id      = e2.tile_id
      AND e2.stop_sequence = e1.stop_sequence + 1
   WHERE e1.created_at >= now() - interval '7 days'
   GROUP BY e2.tile_id;

   Bootstrap and reliability rules (applied per component per nightly run):
   - service_time_min:      < 20 pickup samples in 7-day window   → use city-wide service_time average
   - inter_stop_travel_min: apply in order —
       a. Winsorise each individual pair at tile.traversal_cap_sec before averaging
          (cap is the OSRM SW→NE corner time; guards against detour outliers)
       b. If fewer than MIN_INTER_STOP_PAIRS_PER_WINDOW (default 5) pairs remain in 7-day window
          after winsorisation → use city-wide inter_stop_travel average
          (guards against sparse tiles with statistically meaningless sample sizes)
   - Either component with zero real data           → use bootstrap defaults
       (service_time_default = 12 min, inter_stop_travel_default = 5 min), set is_bootstrapped = true
   - Clear is_bootstrapped only when both components have crossed their respective thresholds.

4. Compute demand_score_minutes for each active tile. Write tile_demand_snapshot rows.

5. Load road-adjacency graph from tile_travel_time.
   This is a DB read — OSRM is not queried live at replan time. The matrix was
   precomputed and stored during the last OSRM refresh (§9).
   If table is empty or stale (> 45 days) → attempt OSRM refresh before proceeding.
   If the refresh fails or OSRM is unreachable → use geometric 4-connectivity,
   set adjacency_source = GEOMETRIC_FALLBACK.
   In steady-state this branch only fires for a brand-new city (first replan
   before OSRM has ever run) or after a failed monthly refresh.

6. Pre-process multi-DA tiles (Component C, §5.4).

7. Run CP-SAT solver with lazy-cuts contiguity (Component B, §5.4).
   Time limit: 60 seconds.
   On timeout or failure → fall back to BFS, set solver_type = BFS_FALLBACK.

8. Post-process: collapse virtual sub-tiles, compute per-region stats.

9. Write DaTileAssignment rows (status = PROPOSED).
   Write AssignmentProposal row (status = PROPOSED, with solver metadata).

10. Notify station manager(s): "New assignment proposal for {city} on {date} awaiting approval."
    Include optimality_gap_pct and any bootstrapped-tile warnings in the notification.

11. If not approved by 06:00 → send escalation alert.
    If not approved by 07:00 → auto-apply yesterday's assignment as fallback;
    log the auto-fallback as an audit event visible to admin.
```

---

## 9. OSRM Road-Time Matrix Refresh

This job is admin-triggered (not nightly). It should run:
- Once at city initialization (before any nightly replan can run)
- Monthly (to pick up road network changes from updated OSM data)
- On admin trigger after major road changes (e.g., new flyover, bridge closure)

```
OSRM Matrix Refresh Job:

1. Load all active tile centroids for the city (computed as in §5.4 Component A).

2. POST to OSRM /table endpoint:
   {
     "sources": [{"location": [lon, lat]} for each active tile],
     "destinations": same
   }
   Returns: durations[][] matrix (seconds).

3. Delete existing tile_travel_time rows for this grid_id.

4. For each pair (i, j) where durations[i][j] <= ADJACENCY_THRESHOLD_SECONDS:
   Insert tile_travel_time(from_tile_id, to_tile_id, travel_time_seconds, computed_at).

5. Log: total pairs stored, pairs where travel_time > threshold (excluded from graph),
   any OSRM errors for specific pairs (null duration = unreachable).

6. If any active tile has zero road-reachable neighbors:
   Log as ISOLATION_WARNING — tile is a spatial island in the road network.
   This tile will get its own DA regardless of demand. Flag for station manager.
```

---

## 10. Grid Initialization (one-time per city)

Triggered by admin action when a new city is onboarded.

```
1. Load serviceable pincodes YAML for the city.

2. Compute bounding box:
   lat_min, lat_max, lon_min, lon_max = extent of all pincode centroids + 1-tile buffer.

3. Compute tile_delta_lat, tile_delta_lon from city center latitude.

4. Generate all tiles (row, col) covering the bounding box. Mark all as inactive.

5. For each pincode centroid (lat, lon):
   tile = getTileAt(lat, lon)
   mark tile as active
   insert PincodeMapping(pincode, tile_id, is_serviceable=true)

6. Generate all grid vertices (M+1) × (N+1).

7. Insert Grid, Tile, GridVertex rows.

8. Trigger OSRM Matrix Refresh Job (§9) — must complete before first nightly replan.

9. Log initialization as an append-only audit event.
```

Grid initialization blocks until the OSRM matrix is available. If OSRM is unavailable at init time, the city is initialized with `adjacency_source = GEOMETRIC_FALLBACK` and the admin is alerted to schedule the OSRM refresh before the first operating day.

---

## 11. Human Approval Workflow

M3 never auto-applies a new DA assignment. The flow is:

```
System proposes → Station manager reviews → Approves or overrides → System activates
```

The proposal detail view shows the station manager:
- Per-region demand estimates, utilization %, and tile count
- `solver_type` and `adjacency_source` (so they know if the algorithm ran in fallback mode)
- `optimality_gap_pct` (so they know the quality bound — e.g., "within 2% of optimal")
- Which tiles are using bootstrapped pickup duration estimates
- Which tiles are understaffed (K_available < K_needed)

If the station manager does not act within the approval window:
- **Before 06:00**: reminder notification
- **07:00 cutoff**: auto-apply yesterday's approved assignment as fallback
- Log the auto-fallback as an audit event visible to admin

Intraday changes (station manager splits or merges a DA territory mid-day) also go through the approval flow with an immediate confirmation window. Every intraday change is audit-logged with approver ID, timestamp, and before/after tile sets. Contiguity is re-validated using the road-adjacency graph on every intraday override.

Two intraday assignment patterns are supported:

- **Intraday override** (`proposal_type = INTRADAY_OVERRIDE`): exclusive tile move — tiles are transferred from one DA to another. The source DA's assignments for those tiles are superseded. Both territories must remain contiguous after the move.
- **Tile share** (`proposal_type = INTRADAY_SHARE`): additive overlay — a second DA is added to a tile without removing the existing DA. `n_das_on_tile` becomes 2. Only one contiguity check is needed: the shared tile must be road-adjacent to at least one tile already in the new DA's territory. Use this when a tile is overloaded intraday and a nearby DA has spare capacity.

---

## 12. Open Questions (from PRD §20, Grid-specific)

| ID | Question | Impact | Status |
|----|----------|--------|--------|
| G1 | Exact meaning of "cron touches a vertex" | Determines how van stops map to grid | Treat as: each van stop must be placed at a grid vertex (row, col). M6 picks the closest vertex to the physical stop. |
| G2 | Tessellation: UTM vs WGS84 for India | Tile shape accuracy | Use WGS84 with per-city Δlon (§3.2). At 2 km scale the projection error is <0.5% — acceptable. |
| G3 | Can 1 DA cover N tiles or M DAs cover 1 tile? | Core algorithm question | **Resolved**: yes to both. N-tile case: contiguity constraint enforced by solver. M-DA case: multi-DA pre-processing (§5.4 Component C). |
| G4 | Approval SLA: auto-accept in "green band" vs always approve | Ops policy | Recommend: always approve for nightly plans. Auto-accept only if `optimality_gap_pct < 1%` AND `solver_type = CP_SAT` AND DA count unchanged from previous day. Confirm with station managers. |
| G5 | OSRM `ADJACENCY_THRESHOLD_SECONDS` calibration | Tile neighbor definition | Default 600 s (10 min). Should be calibrated per city based on average tile traversal time. Cities with dense traffic (Mumbai) may need lower threshold. |
| G6 | CP-SAT `LOAD_TOLERANCE` calibration | Solver balance quality | Default ±30%. Too tight → solver may be infeasible (not enough tiles to balance exactly). Too loose → some DAs are significantly overloaded. Calibrate from first 2 weeks of ops data. |
| G7 | Bootstrap `order_engaged_min_default` per-city calibration | Day-one territory sizing | Default is 17 min/order (12 min service + 5 min inter-stop travel). Service time may be 15–18 min in traffic-dense high-rise zones. Inter-stop travel ranges 3–10 min depending on tile density and road connectivity — sparse suburban tiles will trend higher. Calibrate both components independently per city before go-live using ops estimates. |

---

## 13. Interactions with Other Modules

```
M4 (Orders)
  → writes shipment records with pickup_tile_id (resolved at booking via M3 serviceability check)
  → M3 reads order history from M4 for nightly demand scoring
  → M3 reads per-tile service_time_min and inter_stop_travel_min from M4 GPS event log

M5 (DA Dispatch)
  ← reads da_tile_assignment map from M3 at shift start (full load, cached in memory)
  ← reads tile_for_gps API for real-time tile lookup
  ← reads n_das_on_tile to split intra-tile dispatch queues for high-demand tiles
  ← reads tile load-score API (§7.8) to decide when cross-territory dispatch is warranted
  ← consumes grid.assignment_updated (Kafka, §7.10): patches in-memory cache for affected DAs only on intraday approval
  → on no-DA-found, triggers M3 to emit no_da_alert
  → publishes dispatch.tile_queue_depth (Kafka): per-tile unserved order count, emitted every 5 minutes during shift hours
    Used by M3's IntradayMonitorJob to compute live load scores and detect overloads

M5 query for current assignments (does not reference proposal_id):
  SELECT da_id, tile_id, n_das_on_tile
  FROM da_tile_assignment
  WHERE valid_date = CURRENT_DATE AND status = 'ACTIVE'

M6 (Van Routing)
  ← reads grid_vertices from M3
  Uses vertices as the routable node set for the van graph

M10 (SLA Monitoring)
  ← consumes grid.no_da_alert Kafka events
  Escalates to station manager if a tile has no DA at shift start

OSRM (external, self-hosted)
  ← M3 queries /table endpoint at grid initialization and monthly refresh
  Not queried at replan time — matrix is pre-stored in tile_travel_time
```

---

## 14. Implementation Notes

### 14.1 Package layout

```
com.oneday.grid/
  api/
    GridController.java           -- serviceability, tile-at, vertices, assignments
    ProposalController.java       -- proposal CRUD and approval
  service/
    GridService.java              -- interface
    GridServiceImpl.java          -- tile lookup, pincode mapping (package-private)
    AssignmentService.java        -- interface (swappable: CP-SAT or BFS fallback)
    CpSatAssignmentServiceImpl.java   -- OR-Tools CP-SAT solver (package-private)
    BfsAssignmentServiceImpl.java     -- BFS fallback (package-private)
    OsrmMatrixService.java        -- interface
    OsrmMatrixServiceImpl.java    -- OSRM /table calls and tile_travel_time writes (package-private)
  domain/
    Grid.java
    Tile.java
    TileDemandSnapshot.java
    PincodeMapping.java
    DaTileAssignment.java
    AssignmentProposal.java
    GridVertex.java
    TileTravelTime.java
  repository/
    GridRepository.java
    TileRepository.java
    PincodeMappingRepository.java
    DaTileAssignmentRepository.java
    AssignmentProposalRepository.java
    GridVertexRepository.java
    TileTravelTimeRepository.java
  events/
    NoDaAlertProducer.java            -- publishes to grid.no_da_alert topic
    TileOverloadAlertProducer.java    -- publishes to grid.tile_overload_alert topic
    TileQueueDepthConsumer.java       -- consumes dispatch.tile_queue_depth; updates in-memory load-score map
  dto/
    ServiceabilityResponse.java
    TileAtResponse.java
    AssignmentResponse.java
    TileLoadScoreResponse.java
    ProposalDto.java
    RegionDto.java
  batch/
    NightlyReplanJob.java             -- @Scheduled(cron = "0 0 1 * * *")
    IntradayMonitorJob.java           -- @Scheduled every 5 min during shift hours; checks load scores, emits overload alerts
    OsrmMatrixRefreshJob.java         -- admin-triggered + monthly
    GridInitializationJob.java        -- admin-triggered
```

### 14.2 OR-Tools dependency

OR-Tools ships a Java JAR via Maven Central:

```xml
<dependency>
    <groupId>com.google.ortools</groupId>
    <artifactId>ortools-java</artifactId>
    <version>9.9.3963</version>
</dependency>
```

OR-Tools bundles native binaries for Linux x86_64 and macOS. The nightly job runs on Linux (server); local dev on macOS works out of the box. No separate native lib install required.

`CpSatAssignmentServiceImpl` wraps `com.google.ortools.sat.CpModel` and `CpSolver`. Key classes used:
- `CpModel.newIntVar(0, K-1, "assignment_" + tileId)` — one variable per tile
- `CpModel.addLinearConstraint(LinearExpr.sum(...), lb, ub)` — load-balance per DA
- `CpSolver.solve(model)` — solve with time limit
- `CpSolver.objectiveValue()` — extract result
- `CpSolver.statusName()` — check OPTIMAL / FEASIBLE / UNKNOWN

### 14.3 Caching

- `Grid` and `Tile` rows: cache in memory at startup (static geometry, never changes).
- `PincodeMapping`: `HashMap<String, UUID>` keyed by `"city_id:pincode"`.
- `TileTravelTime` (adjacency graph): load into `Map<UUID, List<UUID>>` at replan start. Not kept in memory between replans — the nightly job is the only consumer.
- `DaTileAssignment` for today: cache at shift-start (M5 reads this frequently), evict on approval of new plan.
- **Intraday load scores**: `TileQueueDepthConsumer` maintains a `ConcurrentHashMap<UUID, Integer>` (tile_id → unserved_orders) updated on every `dispatch.tile_queue_depth` event. `IntradayMonitorJob` and the `GET /grid/tiles/{tile_id}/load-score` endpoint read directly from this map — no DB hit. Map is initialised to zero at shift-start and cleared at shift-end.

### 14.4 Algorithm complexity

For a city with ~150 active tiles and ~50 DAs:
- OSRM matrix: 150×150 = 22,500 pairs, one HTTP call — typically <2 seconds
- CP-SAT (load balance only): < 1 second
- Lazy-cuts rounds (2–5 rounds of contiguity checking + re-solve): 5–10 seconds total
- BFS fallback: O(150) — microseconds
- Total nightly job runtime: dominated by DB reads from M4, not the algorithm

### 14.5 OSRM self-hosting

OSRM runs as a Docker container. One instance per deployment (not per city) using a full India OSM extract:

```yaml
# docker-compose excerpt
osrm:
  image: osrm/osrm-backend:v5.27
  volumes:
    - ./osrm-data:/data
  command: osrm-routed --algorithm MLD /data/india-latest.osrm
  ports:
    - "5000:5000"
```

India OSM extract: downloaded from Geofabrik (`india-latest.osm.pbf`, ~700 MB). Pre-process with `osrm-extract` + `osrm-partition` + `osrm-customize` (Multi-Level Dijkstra profile). The preprocessed graph is ~2 GB on disk.

Monthly refresh: download new OSM extract, reprocess, hot-swap the Docker container. The 30-minute preprocessing window is acceptable since `tile_travel_time` is stale-on-query — the nightly job uses the stored matrix, not a live OSRM call.

### 14.6 No PostGIS dependency

Tile geometry is pure arithmetic. Spatial join (pincode centroid → tile) is done once at initialization using integer division. No PostGIS extension needed, which simplifies DB setup.

---

## 15. Out of Scope for v1

- Sub-tile DA assignment (splitting a tile into quadrants) — not needed at 2km scale; multi-DA tiles handled by Component C
- **Automated intraday territory rebalancing** — intraday overload *detection* and *alerting* is in scope (§16.2); automated territory rebalancing *suggestions* are a good-to-have extension (§16.3); fully autonomous resizing is out of scope
- H3 / hexagonal tile migration — future consideration if sub-500m granularity is needed
- Multi-city DA roaming (a DA spanning tiles in two cities) — not supported
- Per-DA vehicle-type routing profiles in OSRM (bike vs tempo vs e-bike) — single profile in v1
- ML demand forecasting — 70/30 weighted average in v1; ML is a phase-2 upgrade path

---

## 16. Intraday Reactivity

The nightly assignment model is intentionally stable — it does not react to same-day demand shifts. This section defines how intraday reactivity is layered on top without touching the nightly algorithm.

Four levels exist. **Level 1 and Level 2 are committed features.** Level 3 is a good-to-have extension that adds value if built but is not required for operations. Level 4 is a long-term architectural option.

---

### 16.1 Intraday manual override — committed, already in v1

Station managers can manually split or merge DA territories at any point during the shift via the approval workflow (§11). The system validates road-adjacency contiguity and activates immediately after approval. Every intraday change is audit-logged.

This is the baseline. It works without any additional infrastructure, but it relies entirely on the station manager noticing a problem. Level 2 provides the signal.

---

### 16.2 Intraday overload detection and alerting — committed

**What it does:** Monitors the live unserved-order count per tile during shift hours, compares it against the nightly demand forecast, and pushes an alert to the station manager when a tile is running significantly behind pace. The alert is actionable: the station manager sees the overloaded tile, its DA, and can immediately invoke the Level 1 manual override from the same screen.

**Dependencies:**
- M5 must publish `dispatch.tile_queue_depth` Kafka events every 5 minutes during shift hours.
- M3 consumes this topic and maintains in-memory load scores (§14.3).

#### 16.2.1 dispatch.tile_queue_depth (Kafka, M5 → M3)

Topic: `dispatch.tile_queue_depth`

```json
{
  "event_type":     "dispatch.tile_queue_depth",
  "city_id":        "...",
  "operating_date": "2026-05-09",
  "snapshot_time":  "2026-05-09T10:30:00+05:30",
  "tile_depths": [
    { "tile_id": "...", "unserved_orders": 7, "in_progress_orders": 2 },
    { "tile_id": "...", "unserved_orders": 0, "in_progress_orders": 1 }
  ]
}
```

This is a full-city snapshot, not per-tile events — one Kafka message per city per 5-minute tick. M3 replaces its in-memory map entirely on each message (last-write wins, no accumulation).

#### 16.2.2 IntradayMonitorJob — logic

```
IntradayMonitorJob (@Scheduled every 5 minutes, active 07:00–20:00 local time):

For each city:
  shift_start      = 07:00 local
  shift_end        = 20:00 local (ops config)
  now              = current time
  shift_elapsed_pct = (now − shift_start) / (shift_end − shift_start)  [0.0–1.0]

  For each active tile T:
    expected_by_now  = demand_score_orders[T] × shift_elapsed_pct
    unserved         = in_memory_unserved_orders[T]
    adjusted_score   = unserved / max(expected_by_now, 1)

    // Hysteresis: only alert if condition has been sustained.
    // sustained_minutes[T] = minutes this tile has been above threshold continuously.
    if adjusted_score >= OVERLOAD_WARNING_THRESHOLD (default: 1.5):
        sustained_minutes[T] += 5
    else:
        sustained_minutes[T] = 0

    if sustained_minutes[T] >= 15 AND last_alert_emitted[T] was > 30 min ago:
        severity = CRITICAL if adjusted_score >= 2.0 OR unserved > DA_daily_capacity
                   else WARNING
        publish grid.tile_overload_alert (§7.9)
        last_alert_emitted[T] = now
```

Key design decisions:
- **5-minute polling, not event-driven**: tiles oscillate above/below the threshold as M5 picks up orders. Polling with hysteresis avoids alert storms during normal intraday variation.
- **`adjusted_score` (pace-adjusted), not raw count**: a tile with 8 unserved orders at 10am (30% through shift) is more alarming than 8 unserved at 4pm (80% through shift). Raw count would produce false alarms in the morning.
- **30-minute re-alert suppression**: prevents the same overloaded tile from spamming notifications. After the initial alert, the station manager has the information; re-alert only if the problem persists beyond the suppression window.
- **`CRITICAL` severity triggers SMS fallback**: push notifications can be missed; SMS ensures station managers are reached for severe overloads.

#### 16.2.3 Intraday load score API (§7.8)

The `GET /grid/tiles/{tile_id}/load-score` endpoint (§7.8) is the read-side companion: station managers and M5 can query the current pace of any tile at any time, not just when an alert fires. The station manager UI can display a live heat-map of tile load scores to give a city-wide picture at a glance.

#### 16.2.4 What the station manager sees

The overload alert notification contains:
- Tile identifier and locality name
- DA assigned to the tile
- Unserved orders vs expected at this time
- Adjusted load score and sustained duration
- Direct deep-link into the intraday override flow for that tile

The station manager decides whether to act (split the tile, borrow an adjacent DA) and triggers a Level 1 manual override if warranted. The system does not make this decision automatically.

---

### 16.3 System-generated intraday rebalancing suggestions — good to have

> **Status: optional extension.** This is not required for operations. Level 2 gives station managers the signal; Level 3 gives them a pre-computed answer. Build it once Level 2 is proven in production and station managers are regularly acting on alerts.

**What it adds:** When a `CRITICAL` overload alert fires, M3 immediately computes a lightweight local rebalancing suggestion — "here is a specific tile reassignment that would relieve this overload" — and surfaces it alongside the alert as a one-tap approval action.

**Algorithm (local BFS, not full CP-SAT):**

```
On CRITICAL alert for tile T assigned to DA_a:

1. Find all DAs whose territory is road-adjacent to T.
2. For each candidate DA_b:
   a. Compute DA_b's current load: in_memory_unserved[DA_b_tiles] total.
   b. If DA_b's current load < DA_target_load × 0.60 (has meaningful spare capacity):
      Candidate move: reassign T from DA_a to DA_b.
      Validate contiguity: DA_a territory without T must still be connected.
                           DA_b territory with T must be connected.
      If both valid: score this move as (DA_a_relief − DA_b_burden).
3. Select the highest-scoring valid move.
4. If a valid move exists:
   Generate a partial AssignmentProposal (proposal_type = INTRADAY_SUGGESTION)
   with a short approval window (10 minutes).
   Attach it to the overload alert notification: "Suggested fix: move tile X to DA Y. Approve?"
5. If no valid move (DA_a is isolated, no adjacent DA has capacity):
   Alert only — no suggestion. Station manager must decide manually.
```

**What changes vs Level 2:**
- `IntradayMonitorJob` is extended to run the local BFS suggestion after emitting a `CRITICAL` alert.
- A new `proposal_type = INTRADAY_SUGGESTION` is added to `assignment_proposal` (new enum value, no schema migration needed for the nightly `NIGHTLY` type).
- The station manager notification includes an approve/reject action inline.
- If approved, the override activates immediately (same path as Level 1 manual override).

**Why it's optional:** The logic is simple (local BFS, not CP-SAT), but the ops value depends on whether station managers actually trust and use auto-suggestions. If they consistently override or ignore suggestions, the complexity is not justified. Validate Level 2 usage patterns first.

---

### 16.4 Cross-territory dispatch without territory redrawing — long-term (M5 domain)

Instead of redrawing territory boundaries, M5 can dispatch an individual order to a DA outside their home territory when that DA is physically close and has remaining capacity. Territory assignments do not change; the dispatch routing policy does.

**M3's role in this:** The `GET /grid/tiles/{tile_id}/load-score` endpoint (§7.8) is already the right hook. M5 reads `adjusted_load_score` for a tile before deciding whether to cross its boundary. M3 has no additional work here — the API is built as part of Level 2.

This is a phase-2 M5 feature, not an M3 feature.

### 16.5 Full dynamic territory resizing — long-term, high complexity

The Swiggy/Blinkit model: DA territory boundaries re-optimise continuously (every 15–30 minutes) based on real-time demand. Requires ML demand forecasting, live OSRM queries, removal of human approval for minor changes, and significant station manager tooling investment.

Not planned. The nightly model with Level 2 alerting and Level 1 manual overrides must prove insufficient before this investment is justified.

---

## 17. Open Edge Cases & Gaps

| # | Question | Resolution |
|---|----------|------------|
| E1 | **Island tiles**: disconnected active tile clusters break van routing | For gaps ≤ 2 inactive tiles: auto-activate bridge tiles at initialization. For larger gaps: treat as separate service zones with independent van routes. Flag at initialization. |
| E2 | **Intraday unprocessed pickups** | Failed/unserved orders go through M11 exception flow. M3's demand scoring must count *attempted* orders (not just completed) so the next nightly replan correctly sizes capacity. |
| E3 | **Large pincode spanning multiple tiles** | Centroid-based mapping is acceptable at 2km tile size. Flag pincodes where centroid-to-edge distance > 1km for manual review. |
| E4 | **DA absent mid-shift** | M5 detects inactivity (no GPS heartbeat for N minutes), emits `da.absent`. M3 fires `grid.no_da_alert` for affected tiles. Station manager manually reassigns intraday. |
| E5 | **New pincode outside grid bounding box** | `getTileAt()` returns out-of-bounds. Admin triggers grid re-expansion job. Existing tiles and assignments unaffected. |
| E6 | **Demand score bootstrapping for new tiles** | Seed with average demand of active neighbors, or city-wide average as floor. Flag as `is_bootstrapped = true` in snapshot. |
| E7 | **Pickup vs delivery grid symmetry** | Same tile definitions apply at origin and destination cities. DA assignments are city-specific and independent for pickup and delivery roles. |
| E8 | **M4 order feed unavailable at replan time** | Retry 3× with 15-min backoff. If still unavailable by 02:30: carry forward yesterday's demand snapshot (flagged as stale). Continue proposal generation with `stale_demand_data = true` flag. |
| E9 | **Tile with near-zero demand for extended period** | After 14 consecutive days below 2 orders/day, surface a "consider deactivating" recommendation to station manager. Never auto-deactivate. |
| E10 | **Van stop vertices vs traversal-only vertices** | M3 exposes all (M+1)×(N+1) vertices. M6 selects actual stops. Future: station managers can mark vertices as non-stoppable in M3. |
| E11 | **CP-SAT infeasibility**: solver returns INFEASIBLE (load tolerance too tight for available DAs) | Widen `LOAD_TOLERANCE` by 5% increments up to 50%, re-solve. If still infeasible, fall back to BFS. Alert station manager: "Territory balance constraints could not be fully satisfied — manual review required." |
| E12 | **OSRM matrix partially unavailable**: some tile-pair routes return null (unreachable) | Treat null pairs as travel_time = infinity (not adjacent). If a tile has no OSRM-reachable neighbors, fall back to geometric adjacency for that tile only. Log as ISOLATION_WARNING. |
| E13 | **Newly added active tiles mid-month**: a new pincode activates between OSRM refreshes | New tile has no travel-time entries. Use geometric 4-connectivity for new tile only until next OSRM refresh. Flag the tile's DA territories in proposals with `uses_geometric_adjacency = true`. |
| E14 | **Sparse tile inter-stop travel inflation**: a tile with 2–3 orders/day has far-apart stops that are occasionally routed consecutively, producing a high `inter_stop_travel_per_order` from a tiny sample and inflating `demand_minutes` | Two guards applied at nightly replan: (1) winsorise each measured pair at `tile.traversal_cap_sec` (OSRM SW→NE corner time, precomputed at init) before averaging — eliminates detour outliers; (2) if fewer than `MIN_INTER_STOP_PAIRS_PER_WINDOW` (default 5) pairs survive in the 7-day window, discard the tile-level measurement and use the city-wide `inter_stop_travel` average instead. Both guards combined ensure sparse tiles fall back to a representative city average rather than an anomalous per-tile figure. |

---

## 18. Data Volume & Retention

### 18.1 Expected volume (5-city steady state)

| Table | Rows/day | 3-month total |
|-------|----------|---------------|
| `assignment_proposal` | ~10 | ~900 |
| `assignment_proposal_region` | ~200 | ~18,000 |
| `da_tile_assignment` | ~400 | ~36,000 |
| `tile_demand_snapshot` | ~275 | ~24,750 |
| **Total** | **~885** | **~80,000** |

Assumptions: ~35 DAs/city × 5 cities, ~55 active tiles/city, ~1-2 intraday proposals/city/day on average.

Even at 10× growth (50 active cities, high intraday activity), 3-month rolling volume would be ~800K rows — well within PostgreSQL's comfort zone with proper indexing. Performance is never the driver for the S3 dump; clean backups and predictable DB size are.

### 18.2 Retention policy

**Live DB:** rolling 90-day window. Rows older than 90 days are eligible for archival.

**Archive job (monthly):** run a `COPY ... TO` export of rows where `valid_date < NOW() - INTERVAL '90 days'`, upload to S3 as gzip CSV partitioned by city + month, then DELETE the exported rows only after confirming the S3 write succeeded. This is the only operation that issues DELETE on these tables.

**S3 path convention:** `s3://<bucket>/grid-archive/{table}/{city_id}/YYYY-MM/`

**Querying history:** application code never needs to query the archive. The S3 files exist for compliance, audit, and ML training (future demand forecasting). Presto/Athena can query them if needed.

### 18.3 Recommended indexes

```sql
-- Primary access pattern for M5 and approval flows
CREATE INDEX idx_dta_active_date ON da_tile_assignment(valid_date, status);
CREATE INDEX idx_dta_da_date     ON da_tile_assignment(da_id, valid_date, status);
CREATE INDEX idx_dta_tile_date   ON da_tile_assignment(tile_id, valid_date, status);

-- Proposal lookups
CREATE INDEX idx_ap_city_date    ON assignment_proposal(city_id, valid_for_date);

-- Demand scoring
CREATE INDEX idx_tds_tile_date   ON tile_demand_snapshot(tile_id, snapshot_date);
```
