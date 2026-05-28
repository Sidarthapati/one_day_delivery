# M3 Grid Module — Service Summaries

All services live under `grid/src/main/java/com/oneday/grid/service/`. Each section covers the public interface contract followed by a deep-dive into the implementation.

---

## Table of Contents

1. [AssignmentService](#1-assignmentservice)
   - [BfsAssignmentServiceImpl](#bfsassignmentserviceimpl)
   - [CpSatAssignmentServiceImpl](#cpsatassignmentserviceimpl)
2. [DemandScoringService](#2-demandscoringservice)
3. [GridService](#3-gridservice)
4. [GridReplanService](#4-gridreplanservice)
5. [IntradayLoadScoreService](#5-intradayloadscoreservice)
6. [OsrmMatrixService](#6-osrmmatrixservice)
7. [ProposalService](#7-proposalservice)
8. [Supporting Utilities](#8-supporting-utilities)
   - [ContiguityValidator](#contiguityvalidator)
   - [OsrmClient](#osrmclient)
   - [TileEdge / OsrmTableResponse](#tileedge--osrmtableresponse)

---

## 1. AssignmentService

**Interface:** `service/AssignmentService.java`

```
AssignmentProposal computeProposal(
    UUID cityId,
    LocalDate validForDate,
    List<TileDemandSnapshot> demand,
    Map<UUID, List<UUID>> adjacencyGraph,
    List<UUID> availableDaIds
)
```

Single entry point: given a demand snapshot and an adjacency graph for a city, assign every active tile to one DA, then persist and return the resulting `AssignmentProposal` (with linked `AssignmentProposalRegion` + `DaTileAssignment` rows).

Two concrete implementations exist, selected by Spring qualifier:

---

### BfsAssignmentServiceImpl

**Qualifier:** `bfsAssignmentService`  
**Solver type stored:** `SolverType.BFS_FALLBACK`

#### What it does

A greedy, single-pass BFS expander that runs in O(T log T) time. Used as:
- The **primary solver** when `K > nTiles` (more DAs than tiles).
- The **fallback** when CP-SAT reports infeasible or times out.

#### Algorithm step by step

1. **Seed selection** — For each DA in order, pick the highest-demand unassigned tile as the seed.
2. **BFS expansion** — A max-priority-queue frontier expands outward from the seed, always pulling the highest-demand reachable neighbor next.
3. **Hard ceiling** — A tile is skipped if adding it would exceed `shiftMinutes × maxUtilisation` for this DA (once the territory is non-empty — the first tile is always taken to prevent empty territories).
4. **Target stop** — Expansion stops as soon as accumulated load ≥ `shiftMinutes × targetUtilisation`.
5. **Contiguity** — Enforced implicitly: a candidate tile is only admitted if at least one of its road-adjacency-graph neighbors is already in the territory (seed is exempt).
6. **Remaining tiles** — Any tiles still unassigned after all DAs have been processed are recorded as understaffed.

#### Output persisted

| Table | Rows created |
|---|---|
| `assignment_proposal` | 1 row, `solver_type = BFS_FALLBACK`, no optimality gap |
| `assignment_proposal_region` | 1 per DA with assigned tiles |
| `da_tile_assignment` | 1 per (DA, tile) pair, status `PROPOSED` |

---

### CpSatAssignmentServiceImpl

**Qualifier:** `cpSatAssignmentService`  
**Solver type stored:** `SolverType.CP_SAT`

#### What it does

Uses Google OR-Tools CP-SAT (constraint programming / SAT) to find a provably near-optimal assignment. Targets load balance (minimise max−min spread) while enforcing geographic contiguity via a lazy-cuts loop.

#### Algorithm step by step

**1. Pre-processing**
- Demand scores are scaled × 100 to integers (CP-SAT is integer-only).
- The top-K highest-demand tiles are selected as seeds (one per DA) for symmetry breaking.
- Guard rails: if `K > nTiles` or inputs are empty → delegates to BFS.

**2. CP-SAT model construction**

| Element | Description |
|---|---|
| Decision variables | `b[i][k]` — boolean, true iff tile `i` is assigned to DA `k` |
| Coverage constraint | `addExactlyOne` per tile — every tile goes to exactly one DA |
| Load variables | `load[k] = Σ b[i][k] * scaledDemand[i]` |
| Load band constraint | `scaledLb ≤ load[k] ≤ scaledUb`, where bounds = `targetLoad × (1 ± tolerance)` |
| Objective | Minimize `maxLoad − minLoad` (equalise workload across DAs) |
| Symmetry breaking | Pin: seed tile `k` must be assigned to DA `k` |

**3. Lazy contiguity cuts loop** (up to 10 rounds)

CP-SAT has no native geographic constraint, so contiguity is enforced iteratively:
1. Solve the current model.
2. Extract territories — map each DA to its list of tile indices.
3. Run BFS (`ContiguityValidator.findConnectedComponents`) on each territory.
4. If all territories are connected → accept and return.
5. Otherwise, for each disconnected component (the orphaned island, not the one containing the seed), add a cut:
   ```
   Σ b[tile_in_island][k]  ≤  island_size − 1
   ```
   This forbids that exact set from all being assigned to DA k simultaneously.
6. Re-solve with the additional cuts.

If after 10 rounds contiguity is still not achieved, the best available solution is used.

**4. Infeasibility retry loop** (up to 3 retries)

If the solver reports `INFEASIBLE`, the load tolerance is widened by 5 percentage points per attempt before retrying. After 3 failures, falls back to BFS.

**5. Timeout / final fallback**

If the solver times out, or all retries are exhausted, delegates to `BfsAssignmentServiceImpl`.

#### Output persisted

| Table | Rows created |
|---|---|
| `assignment_proposal` | 1 row with `optimality_gap_pct` = `(obj − bestBound) / obj × 100` |
| `assignment_proposal_region` | 1 per DA |
| `da_tile_assignment` | 1 per (DA, tile) pair, status `PROPOSED` |

#### Key constants

| Constant | Value | Purpose |
|---|---|---|
| `MAX_INFEASIBLE_RETRIES` | 3 | Tolerance widening retries before BFS |
| `MAX_LAZY_CUT_ROUNDS` | 10 | Max contiguity fix iterations |
| `DEMAND_SCALE` | 100.0 | Integer scaling factor for demand scores |

---

## 2. DemandScoringService

**Interface:** `service/DemandScoringService.java`  
**Implementation:** `impl/DemandScoringServiceImpl.java`

```
List<TileDemandSnapshot> computeAndPersistDemand(UUID cityId, LocalDate date)
```

Computes a demand score in minutes for every active tile in a city and persists one `TileDemandSnapshot` per tile.

#### Formula

```
demandOrders      = 0.70 × histAvgOrders  +  0.30 × currentOrders
orderEngagedMin   = serviceTimeMin + interStopTravelMin
demandScoreMinutes = demandOrders × orderEngagedMin
```

The 70/30 historical/current weighting is the project-wide demand weighting invariant.

#### Data sources (queried via native SQL against M4 tables)

| Input | Source | Fallback |
|---|---|---|
| `serviceTimeMin` | Avg `(pickup_completed_at − arrived_at_pickup)` per tile, last 7 days, min sample threshold | City-wide average, or `bootstrap.serviceTimeMin` config value |
| `interStopTravelMin` | Avg time between consecutive same-DA same-tile stops, winsorised at `traversal_cap_sec` | City-wide average, or `bootstrap.interStopTravelMin` config value |
| `currentOrders` | Count of `shipment_leg_events` for `shift_date = date` per tile | 0 |
| `histAvgOrders` | Avg daily order count per tile over the 7 days before `date` | 0.0 |

#### Bootstrap mode

When M4 data is unavailable (pre-launch), all four queries return empty maps and the service falls back entirely to the configured bootstrap defaults. Each snapshot is flagged with `bootstrapped = true`, which propagates to `AssignmentProposalRegion.hasBootstrappedTiles`.

---

## 3. GridService

**Interface:** `service/GridService.java`  
**Implementation:** `impl/GridServiceImpl.java`

```
void                        initializeGrid(UUID cityId, String cityCode)
Grid                        getGrid(UUID cityId)
UUID                        resolveCityId(String cityCode)
ServiceabilityResponse      checkServiceability(UUID cityId, String pincode)
TileAtResponse              getTileAt(UUID cityId, double lat, double lon)
List<TileDetailResponse>    getTileDetails(UUID cityId, LocalDate date)
List<GridVertexResponse>    getVertices(UUID cityId)
void                        setTileActive(UUID tileId, boolean active)
List<AssignmentResponse>    getActiveAssignments(UUID cityId, LocalDate date)
```

Manages the static 2 × 2 km rectangular tile grid for a city.

#### `initializeGrid`

One-time setup that:
1. Loads `serviceability/{cityCode}.yaml` from the classpath — a list of pincode entries with lat/lon coordinates.
2. Calculates `tileDeltaLat` and `tileDeltaLon` from a fixed 2 km tile size (accounting for latitude-dependent longitude compression).
3. Determines the bounding box from pincode coordinates with a 1-tile margin.
4. Creates a `Grid` record, then a full `nRows × nCols` grid of `Tile` rows (all inactive initially).
5. Marks tiles containing at least one pincode as active; additionally activates their 4 cardinal neighbors (1-tile geometric buffer for coverage bleed).
6. Creates all `PincodeMapping` rows (serviceable pincodes point to their tile; out-of-bounds pincodes are marked non-serviceable).
7. Creates `GridVertex` rows for all `(nRows+1) × (nCols+1)` lattice corners, storing exact lat/lon for each vertex.
8. Puts the new `Grid` into an in-memory `ConcurrentHashMap` cache keyed by `cityId`.

#### `resolveCityId`

Looks up `grid.cities` config map (e.g., `"delhi"` → `f47ac10b-58cc-4372-a567-0e02b2c3d479`). Throws HTTP 404 if the city code is unknown. Used by all REST controllers to convert the URL path param into a UUID.

#### `getGrid`

Returns the cached `Grid` for a city; throws `IllegalArgumentException` if not found. The cache is populated at startup via `@PostConstruct`.

#### `checkServiceability`

Looks up the `PincodeMapping` table. Returns a `ServiceabilityResponse` with `serviceable=true/false` and the associated `tileId` if serviceable.

#### `getTileAt`

Converts a lat/lon coordinate into a grid `(row, col)` index using the grid's origin and delta values, then fetches the corresponding `Tile`.

#### `getTileDetails`

Returns all tiles for a city with pre-computed lat/lon bounds (SW and NE corners) and today's demand score. Joins `Tile` with `TileDemandSnapshot` for the given date in one batch query. Used by the map UI to draw and colour tiles.

#### `getVertices`

Returns all `GridVertex` rows for the city's grid. Used by the map UI to draw grid-line edges.

#### `setTileActive`

Flips a tile's `is_active` flag. Called by the map UI tile toggle. Used to exclude a tile from nightly replanning (e.g., a known unserviceable area).

#### `getActiveAssignments`

Returns all `DaTileAssignment` rows with status `ACTIVE` for the city on a given date. Scoped to the city's tile set (avoids cross-city contamination). Used by the map UI to colour tiles by assigned DA.

---

## 4. GridReplanService

**Interface:** `service/GridReplanService.java`  
**Implementation:** `impl/GridReplanServiceImpl.java`

```
ProposalResponse replan(UUID cityId, LocalDate validForDate, List<UUID> daIds)
```

Encapsulates the per-city replan logic that was previously private inside `NightlyReplanJob`. Both the nightly scheduled job and the `POST /api/grid/{cityCode}/replan` REST endpoint call this service.

#### What it does

1. Calls `DemandScoringService.computeAndPersistDemand(cityId, date)`.
2. Loads the adjacency graph from `tile_travel_time`. If absent or stale (> 45 days), falls back to geometric 4-connectivity — does **not** trigger `OsrmMatrixRefreshJob`.
3. Logs a warning for any tiles exceeding `DA_max_load` (Component C is deferred).
4. Calls `CpSatAssignmentServiceImpl.computeProposal(...)` with the explicit `daIds` list.
5. Forces `adjacencySource = GEOMETRIC_FALLBACK` on the proposal if the fallback was used.
6. Returns the full `ProposalResponse` via `proposalService.getProposal(proposal.getId())`.

#### Why `daIds` is explicit

`NightlyReplanJob` gets DA IDs from `DaRosterPort` (which returns empty until M1 ships). The REST endpoint accepts them in the `ReplanRequest` body. Keeping the service interface explicit allows both callers to supply the right source without coupling them together.

---

## 5. IntradayLoadScoreService


**Interface:** `service/IntradayLoadScoreService.java`  
**Implementation:** `impl/IntradayLoadScoreServiceImpl.java`

```
TileLoadScoreResponse getLoadScore(UUID tileId, LocalDate date)
void updateQueueDepth(UUID cityId, LocalDate date, Map<UUID, Integer> unservedByTile)
```

Tracks real-time unserved order counts per tile during a live shift and classifies each tile's load severity.

#### State

A single in-memory `ConcurrentHashMap<UUID, Integer>` (`unservedByTile`) maps tile IDs to their current unserved order count. The map is **last-write-wins** — each Kafka event from the order queue replaces the previous value for its tiles.

#### `updateQueueDepth`

Called by `TileQueueDepthConsumer` on each inbound Kafka event. Bulk-puts all entries from the incoming map into the local cache. Thread-safe via `ConcurrentHashMap`.

#### `getLoadScore`

Looks up the tile's current unserved count, wraps it in a `TileLoadScoreResponse` with a severity classification:

| Severity | Condition |
|---|---|
| `CRITICAL` | score ≥ `intraday.overloadCriticalThreshold` |
| `WARNING` | score ≥ `intraday.overloadWarningThreshold` |
| `OK` | below warning threshold |

#### `resetForShift`

Package-private method called by `IntradayMonitorJob` at shift start (07:00) to zero out the previous shift's data.

> **Note:** Until M4 is live, `adjustedLoadScore` is simply the raw unserved count. Once M4 provides expected-by-now baselines, this will be adjusted to a relative overload metric.

---

## 6. OsrmMatrixService

**Interface:** `service/OsrmMatrixService.java`  
**Implementation:** `impl/OsrmMatrixServiceImpl.java`

```
Map<UUID, List<TileEdge>> computeAdjacencyMatrix(UUID cityId)
Map<UUID, Integer>        computeTraversalCaps(UUID cityId)
```

Queries a self-hosted OSRM instance to build road-time-based data structures for the tile grid.

#### `computeAdjacencyMatrix`

Produces the adjacency graph used by both assignment solvers and `ProposalService`:

1. Computes the centroid of each active tile: `(originLat + (row + 0.5) × deltaLat, ...)`.
2. Sends all centroids to OSRM `/table/v1/driving` in a single batch call, receiving an N×N duration matrix in seconds.
3. Builds edges: tile `i` → tile `j` if `0 < duration[i][j] ≤ adjacencyThresholdSeconds`.
4. Logs a `ISOLATION_WARNING` for any tile that has zero reachable neighbors (these tiles will be assigned their own DA).

Returns: `Map<tileId, List<TileEdge>>` where each `TileEdge` carries `(toTileId, travelTimeSec)`.

#### `computeTraversalCaps`

For each active tile, calls OSRM `/route/v1/driving` from SW corner to NE corner to get the road traversal time in seconds. This cap is stored as `tile.traversal_cap_sec` and is used by `DemandScoringServiceImpl` to winsorise inter-stop travel time outliers.

---

## 7. ProposalService

**Interface:** `service/ProposalService.java`  
**Implementation:** `impl/ProposalServiceImpl.java`

Manages the full lifecycle of assignment proposals — from nightly approval to intraday manual overrides.

#### Read operations

| Method | Description |
|---|---|
| `getProposal(id)` | Fetch one proposal with its regions and tile lists |
| `getProposals(cityId, date)` | Fetch all proposals for a city+date |

#### Nightly proposal lifecycle

**`approve(proposalId, reviewerId)`**
1. Validates proposal is in `PROPOSED` status.
2. Supersedes any currently `APPROVED` proposal for the same city+date (sets them to `SUPERSEDED` and marks their assignments `SUPERSEDED`).
3. Transitions this proposal's assignments from `PROPOSED` → `APPROVED`.
4. Marks the proposal `APPROVED`, recording the reviewer and timestamp.

**`reject(proposalId, reviewerId, notes)`**
- Validates `PROPOSED` status, then sets the proposal to `REJECTED` with reviewer notes.

#### Scenario A — Pre-approval region edit

**`editRegionInProposal(proposalId, daId, newTileIds, reviewerId)`**
- Requires proposal still in `PROPOSED` status.
- Validates the new tile set is contiguous using `ContiguityValidator.isConnected` against the persisted `TileTravelTime` adjacency graph.
- Supersedes the DA's existing `PROPOSED` assignment rows under this proposal (sets them to `SUPERSEDED`).
- Inserts fresh `DaTileAssignment` rows for the replacement tile set, status `PROPOSED`.
- Updates the linked `AssignmentProposalRegion`.

#### Scenario B — Intraday tile reassignment

**`requestIntradayReassignment(cityId, fromDaId, toDaId, tileIdsToMove, requestedBy)`**
- Validates all tiles to move are currently `ACTIVE` under `fromDaId`.
- Validates `fromDaId`'s remaining territory stays connected after removal.
- Validates `toDaId`'s extended territory stays connected after addition.
- Creates a new `AssignmentProposal` of type `INTRADAY_OVERRIDE` / status `PROPOSED`.
- Writes full new tile sets for both DAs as `PROPOSED` assignment rows (**append-only — active rows are never mutated**).
- Returns an `IntradayReassignmentResponse`.

**`approveIntradayReassignment(proposalId, reviewerId)`**
- Supersedes `ACTIVE`/`APPROVED` assignments for the two affected DAs on today's date.
- Activates the override proposal's new assignments (`PROPOSED` → `ACTIVE`).
- Marks the override proposal `APPROVED`.

#### Tile share

**`requestTileShare(cityId, daId, tileId, requestedBy)`**
- Validates the tile has at least one `ACTIVE` assignment.
- Creates a new `INTRADAY_SHARE` proposal with a new `DaTileAssignment` row for the additional DA, `nDasOnTile = existing + 1`.

**`approveTileShare(proposalId, reviewerId)`**
- Activates the share assignment row. Does **not** supersede the existing assignment — both DAs now cover the tile simultaneously.

---

## 8. Supporting Utilities

### ContiguityValidator

**File:** `impl/ContiguityValidator.java`  
Not a Spring bean. Shared by `CpSatAssignmentServiceImpl` and `ProposalServiceImpl`.

| Method | Description |
|---|---|
| `isConnected(tileIds, adjacencyGraph)` | Returns true if all tiles form a single connected component using BFS |
| `findConnectedComponents(tileIds, adjacencyGraph)` | Returns all connected components, sorted largest-first by tile count |

Both methods operate on the road adjacency graph (not geometric proximity).

---

### OsrmClient

**File:** `service/osrm/OsrmClient.java`  
Not a Spring bean — constructed directly by `OsrmMatrixServiceImpl`.

| Method | Description |
|---|---|
| `getTable(latLons)` | Calls OSRM `/table/v1/driving` with all centroids in a single request; returns `durations[i][j]` in seconds |
| `getTileTraversalCap(swLat, swLon, neLat, neLon)` | Calls OSRM `/route/v1/driving` SW→NE corner; returns road travel time in seconds, or `null` on failure |

> OSRM expects coordinates as `lon,lat` (not `lat,lon`). The client handles the reversal internally.

---

### TileEdge / OsrmTableResponse

**File:** `service/osrm/TileEdge.java`, `service/osrm/OsrmTableResponse.java`

| Class | Type | Purpose |
|---|---|---|
| `TileEdge` | `record(UUID toTileId, int travelTimeSec)` | Edge in the road adjacency graph |
| `OsrmTableResponse` | `record(String code, double[][] durations)` | Jackson-deserialized OSRM table response |

---

## Service Dependency Map

```
ProposalService
  └─► ContiguityValidator
  └─► TileTravelTimeRepository (adjacency graph from DB)

AssignmentService (CP-SAT)
  └─► ContiguityValidator (lazy-cut check)
  └─► AssignmentService (BFS) ← fallback

AssignmentService (BFS)
  └─► (no service deps — operates on in-memory data)

DemandScoringService
  └─► GridService (to get active tiles)
  └─► EntityManager (native SQL against M4 tables)

OsrmMatrixService
  └─► GridService (tile centroids)
  └─► OsrmClient (HTTP)

IntradayLoadScoreService
  └─► (stateless except in-memory map; fed by Kafka consumer)

GridService
  └─► ResourceLoader (YAML configs)
  └─► (pure DB ops; no service deps)
```
