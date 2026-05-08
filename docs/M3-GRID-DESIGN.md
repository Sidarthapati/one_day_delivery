# M3 — Serviceability & Grid Management: Design Doc

| Field | Value |
|-------|-------|
| Module | M3 — grid |
| Status | Draft v0.1 |
| Author | Design session 2026-05-08 |
| Depends on | common, M4 (order history feed) |
| Consumed by | M4 (serviceability check), M5 (DA-to-tile map), M6 (grid vertices) |

---

## 1. What M3 Does

M3 owns two distinct but related concerns:

1. **Serviceability**: given a (city, pincode) pair, answer whether we serve it — used by M4 at booking time.
2. **Grid management**: define the fixed 2 km × 2 km rectangular tile geometry, maintain the nightly DA-to-tile assignment, and expose tile/vertex data to M5 and M6.

It is the spatial foundation every other module stands on.

---

## 2. Industry Context — How Other Companies Handle This

### 2.1 What major logistics players do

| Company | Approach | Notes |
|---------|----------|-------|
| **Amazon Logistics** | Irregular service areas ("routes") per driver, computed by routing solver nightly | No regular grid; each "route" is a TSP-optimized polygon. Complex to rebalance. |
| **FedEx Ground** | Fixed service-area polygons per terminal, driver routes are daily TSP optimizations within polygon | Zones don't change; driver routes inside do. Closest to our model. |
| **USPS** | "Carrier Routes" — fixed irregular polygons defined by historical mail volume | Very stable zones, rarely redrawn. Density drives route length, not zone size. |
| **Delhivery / Shadowfax (India)** | Pincode clusters assigned per hub, no spatial grid | Hub-and-spoke; serviceable pincodes drive assignment, not geometry. |
| **Swiggy / Blinkit (India)** | Variable-size H3 hexagonal tiles; zone sizes shrink in high-density areas | H3 (Uber's library) gives uniform coverage but is harder to explain to ops. |
| **Uber Eats** | H3 cells for surge pricing and positioning nudges; not for driver zone assignment per se | Driver zones are polygon-based at Uber Eats scale. |
| **DoorDash** | Geohash-based rectangles; adjusts resolution per market density | Geohash is hierarchical; level 6 ≈ 1.2 km × 0.6 km in India — close to our 2 km tile. |

### 2.2 Why we're choosing fixed rectangular tiles

Most companies move to hexagonal or irregular polygons at scale for better spatial uniformity. For v1 we choose **fixed 2 km × 2 km rectangular tiles** for the following reasons:

- **Operational legibility**: station managers can explain a tile to a DA using street names. A hex or Voronoi cell is harder to communicate on the ground.
- **Simple adjacency math**: two tiles (r1,c1) and (r2,c2) are adjacent iff `|r1−r2| + |c1−c2| == 1`. No library dependency.
- **Index-based lookup**: any GPS coordinate maps to a tile via integer division — O(1), no spatial index needed for the common case.
- **Grid vertex alignment with van routes**: M6 uses tile corners as van stops. Rectangular tiles give a clean, regular graph.
- **Indian city structure**: major Indian metros (Delhi, Mumbai, Hyderabad, Bangalore, Chennai) have largely block-based street patterns in their commercial cores, so rectangular tiles align reasonably with natural delivery zones.

The key architectural trade-off is that rectangular tiles are less spatially uniform than hex cells — a corner tile covers the same area but sees less "circular" coverage than a center tile. At 2 km this error is acceptable; if we ever need sub-500m tiles we should revisit H3.

---

## 3. Tile Geometry

### 3.1 Fixed 2 km × 2 km tiles

Tiles are defined once per city and **never change**. The tile **geometry is static**. What changes nightly is **which DAs are assigned to which tiles** (the DA-to-tile assignment map).

This is a deliberate simplification: it decouples "where is the serviceable area" (M3, static) from "how do we staff it" (M3 nightly job + M5 dispatch).

### 3.2 Lat/lon conversion

2 km in angular terms depends on the city's latitude. At latitude φ:

```
Δlat  = 2.0 / 111.32                        ≈ 0.01797°  (constant — 1° lat ≈ 111.32 km)
Δlon  = 2.0 / (111.32 × cos(φ × π / 180))               (varies with city)
```

Representative values for our five target cities (approximate center latitudes):

| City | Approx. latitude | Δlat | Δlon |
|------|-----------------|------|------|
| Delhi | 28.6°N | 0.01797° | 0.02041° |
| Mumbai | 19.1°N | 0.01797° | 0.01901° |
| Bangalore | 12.9°N | 0.01797° | 0.01845° |
| Hyderabad | 17.4°N | 0.01797° | 0.01882° |
| Chennai | 13.1°N | 0.01797° | 0.01847° |

These values are computed once at city-grid initialization and stored as `tile_delta_lat` / `tile_delta_lon` in the `Grid` record. They never change.

### 3.3 Tile indexing

Given a city grid anchored at (origin_lat, origin_lon) (the south-west corner of the bounding box of the serviceable area), a tile is identified by its integer index (row, col) where:

```
row = floor((lat  − origin_lat) / tile_delta_lat)
col = floor((lon  − origin_lon) / tile_delta_lon)
```

This makes GPS → tile lookup O(1): two subtractions and two integer divisions.

### 3.4 Active vs inactive tiles

Not all tiles in the bounding box are serviceable. A tile is **active** if at least one serviceable pincode's centroid falls within its bounds. Inactive tiles exist in the data model but are invisible to M4/M5/M6.

```
Active tile: contains ≥ 1 serviceable pincode → assigned DAs, used in routing
Inactive tile: outside serviceable area → serviceability check returns false
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
  # ... more pincodes
```

Each pincode entry carries a representative lat/lon (centroid of the pincode polygon, obtained from a postal dataset). This is used to assign the pincode to a tile.

### 4.2 Pincode → tile mapping

At startup (and on admin re-initialization), M3 runs a one-time mapping job:

```
for each serviceable pincode P with centroid (lat, lon):
    tile = getTileAt(city_grid, lat, lon)
    upsert PincodeMapping(pincode=P, city_id, tile_id=tile.id, is_serviceable=true)
```

This mapping is stored in the DB and used for O(1) serviceability checks at booking time.

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
4. The nightly assignment targets **~70% DA utilization** (target from PRD §6.2).
5. Assignment proposals are made by the system nightly and require **human approval** before taking effect (station manager for their city, admin for all cities).

### 5.2 Demand score per tile

Each tile gets a demand score updated nightly:

```
demand_score(tile, date) =
    0.70 × avg_daily_orders(tile, last_7_days)
  + 0.30 × orders_today(tile)
```

This is the 70/30 rule from the PRD. The look-back window is 7 days minimum; the system can extend to 14 or 28 days once enough data accumulates.

For new tiles with no history, `avg_daily_orders = 0` and we rely entirely on the 30% current signal, or fall back to a city-level average if the current day has no data.

### 5.3 DA capacity

```
DA_daily_capacity  = (shift_hours × avg_orders_per_hour) — expressed as max orders per day
DA_target_load     = DA_daily_capacity × 0.70
```

`DA_daily_capacity` is an ops configuration constant (same for all DAs in v1, potentially per-DA in later phases). A reasonable starting point: 40 orders/day for a bike DA on a two-wheel → `DA_target_load = 28 orders/day`.

### 5.4 Assignment algorithm (nightly replan)

This is the key algorithm. We reduce the problem to:

> **Given** a graph G of active tiles with demand weights, and K available DAs, **partition** G into K contiguous subgraphs such that each subgraph's total demand is as close to `DA_target_load` as possible.

This is a constrained graph partitioning problem. At city scale (50–200 active tiles), a BFS-based greedy algorithm is efficient and produces good results.

#### Step 0: Pre-computation

```
1. Build tile adjacency graph:
   Tiles (r1,c1) and (r2,c2) are adjacent iff |r1−r2| + |c1−c2| = 1
   (4-connectivity: north, south, east, west — no diagonals)

2. Compute demand_score for each active tile

3. Estimate K_needed = ceil(sum(demand_score over all tiles) / DA_target_load)
   This is the minimum number of DAs needed.
   Actual K_available = number of active DAs for this city for tomorrow.
   If K_available < K_needed → flag for station manager (understaffed).
```

#### Step 1: Seed selection

```
Sort active tiles by demand_score descending.
The highest-demand tiles become seeds for DA regions.
```

#### Step 2: BFS region growing

```
UNASSIGNED = set of all active tiles
REGIONS = []

while UNASSIGNED is not empty:
    seed = highest-demand tile in UNASSIGNED
    region = {seed}
    queue = [seed]
    region_demand = demand_score[seed]

    while queue is not empty AND region_demand < DA_target_load:
        current = dequeue(queue)
        for each neighbor of current that is UNASSIGNED and not in region:
            if region_demand + demand_score[neighbor] <= DA_target_load * 1.3:
                # 1.3 slack allows slight overage rather than leaving isolated tiles
                add neighbor to region
                add neighbor to queue
                region_demand += demand_score[neighbor]

    mark all tiles in region as ASSIGNED
    REGIONS.append(region)

return REGIONS
```

This is O(n) where n = number of active tiles. The 1.3 slack factor avoids leaving isolated tiles that would each require their own DA.

#### Step 3: Handle high-demand tiles (multi-DA assignment)

If a single tile's demand_score > `DA_target_load × 1.5`, it needs multiple DAs:

```
for each tile T where demand_score[T] > DA_target_load * 1.5:
    n_das = ceil(demand_score[T] / DA_target_load)
    assign n_das DAs to tile T
    # M5 handles intra-tile routing (closer-first queue split)
```

Multiple DAs on one tile don't require further subdivision in M3 — they share the tile and M5's dispatch logic routes each order to the closest available DA.

#### Step 4: Handle low-demand regions (merge)

If a region's total demand < `DA_target_load × 0.3`, it is under-loaded. Attempt to merge with an adjacent region:

```
for each region R where region_demand < DA_target_load * 0.3:
    candidate = adjacent region with lowest current demand
    if candidate.demand + R.demand <= DA_target_load * 1.3:
        merge R into candidate (one DA covers both)
    else:
        keep R as-is (sparse area, 1 DA with low utilization)
        # This will be flagged in the utilization report for station manager review
```

#### Step 5: Output proposal

The algorithm produces a **proposal** (not an active assignment):

```
AssignmentProposal {
    city_id,
    valid_for_date (tomorrow),
    regions: [
        {
            region_id,
            tile_ids: [...],
            n_das_required: int,
            estimated_demand: double,
            estimated_utilization: double  // estimated_demand / (n_das * DA_daily_capacity)
        }
    ],
    total_das_required,
    understaffed_tiles: [...],  // tiles with no available DA
    status: PROPOSED
}
```

This proposal is shown to the station manager for approval. They can accept as-is, adjust specific tile assignments manually, or reject and trigger a re-run.

### 5.5 Adjacency invariant enforcement

When a station manager manually overrides a DA assignment (e.g. moves a tile from one DA to another), M3 validates:

```
validateConnected(tile_set):
    Build subgraph of tile_set using adjacency edges.
    Run BFS from any tile.
    If all tiles reachable → connected (valid).
    Else → reject with error: "DA territory must be contiguous".
```

This check runs in O(t) where t = number of tiles in the DA's territory.

---

## 6. Data Model

### 6.1 Grid

```sql
CREATE TABLE grid (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id      UUID NOT NULL,
    origin_lat   DOUBLE PRECISION NOT NULL,   -- SW corner of bounding box
    origin_lon   DOUBLE PRECISION NOT NULL,
    tile_delta_lat DOUBLE PRECISION NOT NULL, -- ≈ 0.01797°
    tile_delta_lon DOUBLE PRECISION NOT NULL, -- city-specific
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(city_id)
);
```

One row per city. Never updated after creation.

### 6.2 Tile

```sql
CREATE TABLE tile (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grid_id   UUID NOT NULL REFERENCES grid(id),
    row_idx   INT NOT NULL,
    col_idx   INT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT false,
    UNIQUE(grid_id, row_idx, col_idx)
);
```

Geometry is fully derived from `(grid.origin_lat, grid.origin_lon, grid.tile_delta_lat, grid.tile_delta_lon, tile.row_idx, tile.col_idx)`. No PostGIS column needed for tiles themselves — pure arithmetic.

```
tile.lat_min = grid.origin_lat + tile.row_idx * grid.tile_delta_lat
tile.lat_max = grid.origin_lat + (tile.row_idx + 1) * grid.tile_delta_lat
tile.lon_min = grid.origin_lon + tile.col_idx * grid.tile_delta_lon
tile.lon_max = grid.origin_lon + (tile.col_idx + 1) * grid.tile_delta_lon
```

### 6.3 Tile demand snapshot

```sql
CREATE TABLE tile_demand_snapshot (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tile_id         UUID NOT NULL REFERENCES tile(id),
    snapshot_date   DATE NOT NULL,
    hist_avg_orders DOUBLE PRECISION NOT NULL,  -- 7-day rolling average
    current_orders  INT NOT NULL,               -- orders today at snapshot time
    demand_score    DOUBLE PRECISION NOT NULL,  -- 0.7*hist + 0.3*current
    UNIQUE(tile_id, snapshot_date)
);
```

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

### 6.5 DA tile assignment

```sql
CREATE TABLE da_tile_assignment (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    da_id          UUID NOT NULL,
    tile_id        UUID NOT NULL REFERENCES tile(id),
    valid_date     DATE NOT NULL,          -- the operating day this assignment is active
    n_das_on_tile  INT NOT NULL DEFAULT 1, -- how many DAs share this tile on this date
    status         VARCHAR(20) NOT NULL,   -- PROPOSED | APPROVED | ACTIVE | SUPERSEDED
    proposed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by    UUID,                   -- station manager or admin user_id
    approved_at    TIMESTAMPTZ,
    UNIQUE(da_id, tile_id, valid_date)
);
```

Append-only: new rows for each night's plan. Old rows are never deleted or mutated (audit trail).

### 6.6 Assignment proposal

```sql
CREATE TABLE assignment_proposal (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id         UUID NOT NULL,
    valid_for_date  DATE NOT NULL,
    status          VARCHAR(20) NOT NULL,  -- PROPOSED | APPROVED | REJECTED | SUPERSEDED
    total_das       INT NOT NULL,
    coverage_pct    DOUBLE PRECISION,      -- % of tiles with at least one DA
    proposed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by     UUID,
    reviewed_at     TIMESTAMPTZ,
    notes           TEXT
);
```

### 6.7 Grid vertex

```sql
CREATE TABLE grid_vertex (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grid_id  UUID NOT NULL REFERENCES grid(id),
    row_idx  INT NOT NULL,   -- 0 to (max_row + 1) inclusive
    col_idx  INT NOT NULL,   -- 0 to (max_col + 1) inclusive
    lat      DOUBLE PRECISION NOT NULL,
    lon      DOUBLE PRECISION NOT NULL,
    UNIQUE(grid_id, row_idx, col_idx)
);
```

Vertices are pre-computed for M6. A grid with M rows and N columns of tiles has (M+1) × (N+1) vertices.

```
vertex.lat = grid.origin_lat + vertex.row_idx * grid.tile_delta_lat
vertex.lon = grid.origin_lon + vertex.col_idx * grid.tile_delta_lon
```

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

Pure arithmetic — O(1), no DB hit needed if grid config is cached in memory.

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

### 7.6 Assignment proposal operations (consumed by station manager UI)

```
GET  /grid/proposals?city_id={}&date={}          -- list proposals
GET  /grid/proposals/{proposal_id}               -- proposal detail with per-region breakdown
POST /grid/proposals/{proposal_id}/approve       -- approve (station manager / admin)
POST /grid/proposals/{proposal_id}/reject        -- reject with notes
PUT  /grid/proposals/{proposal_id}/regions/{region_id}/override  -- manual tile rearrangement
```

### 7.7 No-DA alert (produced by M3, consumed by M10)

Kafka topic: `grid.no_da_alert`

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

---

## 8. Nightly Replan Job

The nightly batch job runs after midnight and before the first shift starts. It is a Spring `@Scheduled` task in the `grid` module.

```
Nightly Grid Replan (runs at 01:00 local city time):

1. Fetch order history for past 7 days from M4 (via DB query on shipments table,
   reading only pickup tile_id + created_at — no full order data needed).

2. Fetch today's order count per tile (live feed from M4 Kafka topic or DB snapshot).

3. Compute demand_score for each active tile. Write tile_demand_snapshot rows.

4. Run DA-to-tile assignment algorithm (§5.4).

5. Write DATileAssignment rows with status=PROPOSED.

6. Write AssignmentProposal row with status=PROPOSED.

7. Notify station manager(s) via notification system: "New assignment proposal for
   {city} on {date} awaiting approval."

8. If current proposal not approved by 06:00, send escalation alert.
   If not approved by 07:00, auto-apply previous day's assignment as fallback
   and alert station manager that yesterday's plan is running.
```

**Fallback for missing approval**: yesterday's approved assignment is kept active. This avoids the system going unserviced due to a delayed approval.

---

## 9. Grid Initialization (one-time per city)

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

8. Log initialization as an append-only audit event.
```

---

## 10. Human Approval Workflow

M3 never auto-applies a new DA assignment. The flow is:

```
System proposes → Station manager reviews → Approves or overrides → System activates
```

If the station manager does not act within the approval window:
- **Before 06:00**: reminder notification
- **07:00 cutoff**: auto-apply yesterday's assignment as fallback (see §8)
- Log the auto-fallback as an audit event visible to admin

Intraday changes (station manager splits or merges a DA territory mid-day) also go through the approval flow, but use a shorter confirmation window (no overnight batch needed — applies immediately after approval). Every intraday change is audit-logged with approver ID, timestamp, and before/after tile sets.

---

## 11. Open Questions (from PRD §20, Grid-specific)

| ID | Question | Impact | Suggested Resolution |
|----|----------|--------|----------------------|
| G1 | Exact meaning of "cron touches a vertex" | Determines how van stops map to grid | Treat as: each van stop must be placed at a grid vertex (row, col). M6 picks the closest vertex to the physical stop. |
| G2 | Tessellation: UTM vs WGS84 for India | Tile shape accuracy | Use WGS84 with per-city Δlon (§3.2). At 2 km scale the projection error is <0.5% — acceptable. Revisit for <500m tiles. |
| G3 | Can 1 DA cover N tiles or M DAs cover 1 tile? | Core algorithm question | **Answered in this doc**: yes to both, subject to adjacency constraint for N-tile case. |
| G4 | Approval SLA: auto-accept in "green band" vs always approve | Ops policy | Recommend: always approve for nightly plans (low friction); auto-accept only for minor delta (< 5% change in DA count) — to be confirmed with station managers. |

---

## 12. Interactions with Other Modules

```
M4 (Orders)
  → writes shipment records with pickup_tile_id (resolved at booking via M3 serviceability check)
  → M3 reads order history from M4 for nightly demand scoring

M5 (DA Dispatch)
  ← reads da_tile_assignment map from M3
  ← reads tile_for_gps API for real-time tile lookup
  → on no-DA-found, triggers M3 to emit no_da_alert

M6 (Van Routing)
  ← reads grid_vertices from M3
  Uses vertices as the routable node set for the van graph

M10 (SLA Monitoring)
  ← consumes grid.no_da_alert Kafka events
  Escalates to station manager if a tile has no DA at shift start
```

---

## 13. Implementation Notes (for engineers)

### 13.1 Package layout

```
com.oneday.grid/
  api/
    GridController.java          -- serviceability, tile-at, vertices, assignments
    ProposalController.java      -- proposal CRUD and approval
  service/
    GridService.java             -- interface
    GridServiceImpl.java         -- tile lookup, pincode mapping (package-private)
    AssignmentService.java       -- interface
    AssignmentServiceImpl.java   -- nightly algorithm (package-private)
  domain/
    Grid.java
    Tile.java
    TileDemandSnapshot.java
    PincodeMapping.java
    DaTileAssignment.java
    AssignmentProposal.java
    GridVertex.java
  repository/
    GridRepository.java
    TileRepository.java
    PincodeMappingRepository.java
    DaTileAssignmentRepository.java
    AssignmentProposalRepository.java
    GridVertexRepository.java
  events/
    NoDaAlertProducer.java       -- publishes to grid.no_da_alert topic
  dto/
    ServiceabilityResponse.java
    TileAtResponse.java
    AssignmentResponse.java
    ProposalDto.java
    RegionDto.java
  batch/
    NightlyReplanJob.java        -- @Scheduled(cron = "0 0 1 * * *")
    GridInitializationJob.java   -- admin-triggered
```

### 13.2 Caching

- `Grid` and `Tile` rows: cache in memory at startup (they never change).
- `PincodeMapping`: cache as a `HashMap<String, UUID>` keyed by `"city_id:pincode"`.
- `DaTileAssignment` for today: cache at shift-start, evict on approval of new plan.

### 13.3 No PostGIS dependency

Tile geometry is pure arithmetic. Spatial join (pincode centroid → tile) is done once at initialization using integer division. No PostGIS extension needed, which simplifies the DB setup for v1.

### 13.4 Algorithm complexity

For a city with ~150 active tiles and ~50 DAs:
- Adjacency graph: 150 nodes, ~300 edges — trivial
- BFS region growing: O(150) — runs in microseconds
- Total nightly job runtime: dominated by DB reads from M4, not the algorithm

---

## 14. Out of Scope for v1

- Sub-tile DA assignment (splitting a tile into quadrants) — not needed at 2km scale
- Real-time tile resizing based on intraday demand — nightly stability is a hard requirement
- H3 / hexagonal tile migration — future consideration if sub-500m granularity is needed
- Multi-city DA roaming (a DA spanning tiles in two cities) — not supported

---

## 15. Open Edge Cases & Gaps (to be resolved before PoC)

| # | Question | Possible Solution |
|---|----------|-------------------|
| E1 | **Island tiles**: serviceable pincodes in two disconnected urban clusters produce disconnected active tile graphs — BFS region growing handles each island independently but M6 van routing breaks. | For small gaps (≤2 inactive tiles): auto-activate "bridge tiles" (no pincodes, just connectivity). For large gaps: treat as separate service zones with independent van routes. Flag both at initialization for station manager review. |
| E2 | **Intraday unprocessed pickups**: if a DA's queue overflows during the day and orders can't be served, where do they go? | Not automatic carryover — failed/unserved orders go through M11 exception flow (call center → customer confirmation → reschedule). M3's role: demand scoring must count all *attempted* orders per tile (not just completed), so the next nightly replan correctly sizes DA capacity for that tile. |
| E3 | **Large pincode spanning multiple tiles**: some Indian pincodes cover 3–5 km² and may straddle tile boundaries — centroid-based mapping assigns the whole pincode to one tile, but physical pickups near the edge land in a different tile's DA territory. | Acceptable approximation for v1 at 2 km tile size. For accuracy, split large pincodes into sub-localities with individual centroids (data enrichment task, not a system change). Flag pincodes where centroid-to-edge distance > 1 km. |
| E4 | **DA absent mid-shift**: a DA goes offline after their shift starts — their tiles are now unserved but the nightly assignment still shows them as covered. | M5 detects DA inactivity (no GPS heartbeat for N minutes) and emits a `da.absent` event. M3 reacts by firing a `grid.no_da_alert` for each affected tile. Station manager must manually reassign or merge those tiles to an adjacent DA intraday. |
| E5 | **New pincode added outside current grid bounding box**: admin adds a new serviceable pincode whose centroid falls outside the initialized grid extent. | `getTileAt()` returns out-of-bounds — must be caught. Admin must trigger a grid re-expansion job that extends the bounding box, generates new tile/vertex rows, and marks new pincodes. Existing tiles and assignments are unaffected. |
| E6 | **Demand score bootstrapping for newly activated tiles**: a tile has no historical order data (new pincode, new service area) — 7-day average is zero, demand score is unreliable. | Seed new tiles with the average demand score of their active neighbors, or use city-wide average as a floor. Flag as "bootstrapped" in `tile_demand_snapshot` so the station manager knows the estimate is unreliable. |
| E7 | **Pickup grid vs delivery grid**: M3's grid is used for both origin-side pickup DA assignment and destination-side delivery DA assignment — are the same tiles/DAs used for both directions? | Yes, the grid is symmetric (PRD §10.3). The same tile definitions apply at origin and destination cities. DA assignments are city-specific: each city's station manager assigns DAs for their city independently, for both pickup and delivery roles. |
| E8 | **M4 order feed unavailable at replan time**: if the nightly job can't read from M4 at 01:00 (DB or Kafka down), demand scores can't be recomputed. | Retry up to 3 times with 15-minute backoff. If feed is still unavailable by 02:30, skip demand recomputation and carry forward yesterday's `tile_demand_snapshot`. Log the skip as an alert to admin. Do not block the proposal generation — run it on stale scores with a "stale data" flag visible in the proposal. |
| E9 | **Tile with near-zero demand for extended period**: a tile stays active but consistently scores near zero (e.g. an industrial zone that went quiet) — it still needs a DA assigned, wasting capacity. | After 14 consecutive days below a low-demand threshold (e.g. < 2 orders/day), flag the tile for station manager review with a "consider deactivating" recommendation. Never auto-deactivate — human decision only. |
| E10 | **Van stop vertices vs traversal-only vertices**: M3 exposes all (M+1)×(N+1) vertices to M6, but not every vertex is a practical van stop (some may be inside a building, highway, etc.). | M3 exposes all vertices; M6 is responsible for selecting which are actual stops based on route feasibility. A future enhancement could let station managers mark specific vertices as "non-stoppable" in M3, which M6 would then skip. |
