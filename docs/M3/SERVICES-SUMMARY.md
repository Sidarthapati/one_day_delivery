# M3 Grid Module — Services Summary

> Module: `grid`  
> Package root: `com.oneday.grid`  
> Last updated: May 2026

This document summarises every service interface and its implementation(s) in the `grid` module. Each entry covers the contract (interface), what the impl does internally, its key algorithmic decisions, dependencies, and failure modes.

---

## Table of Contents

1. [GridService / GridServiceImpl](#1-gridservice--gridserviceimpl)
2. [DemandScoringService / DemandScoringServiceImpl](#2-demandscoringservice--demandscoringserviceimpl)
3. [AssignmentService](#3-assignmentservice)
   - [BfsAssignmentServiceImpl](#31-bfsassignmentserviceimpl)
   - [CpSatAssignmentServiceImpl](#32-cpsatassignmentserviceimpl)
4. [ProposalService / ProposalServiceImpl](#4-proposalservice--proposalserviceimpl)
5. [OsrmMatrixService / OsrmMatrixServiceImpl](#5-osrmmatrixservice--osrmmatrixserviceimpl)
6. [IntradayLoadScoreService / IntradayLoadScoreServiceImpl](#6-intradayloadscoreservice--intradayloadscoreserviceimpl)
7. [Supporting Utilities](#7-supporting-utilities)
   - [ContiguityValidator](#71-contiguityvalidator)
   - [OsrmClient](#72-osrmclient)
   - [GridProperties](#73-gridproperties)

---

## 1. GridService / GridServiceImpl

**File:** `service/GridService.java` · `service/impl/GridServiceImpl.java`

### Contract

```
ServiceabilityResponse checkServiceability(UUID cityId, String pincode)
TileAtResponse         getTileAt(UUID cityId, double lat, double lon)
void                   initializeGrid(UUID cityId, String cityCode)
Grid                   getGrid(UUID cityId)
```

### What it does

`GridServiceImpl` is the foundation of the entire module. It manages a city's rectangular tile grid and answers two real-time queries.

**`initializeGrid`** — called once per city at setup:
1. Loads `serviceability/{cityCode}.yaml` from the classpath via `ResourceLoader`.
2. Derives tile dimensions: fixed at **2 km × 2 km** (latitude delta = `2 / 111.32°`, longitude delta corrected for the city's latitude cosine).
3. Computes a bounding box from all serviceable pincode lat/lons plus one tile of padding.
4. Persists a `Grid` row, then all `nRows × nCols` tiles (initially inactive).
5. Maps each pincode to its tile and marks it `active = true`. Also activates the 4 cardinal-direction neighbors of every pincode-mapped tile — a one-tile geometric buffer to handle pincodes that straddle tile edges.
6. Persists all `GridVertex` corner-points `(nRows+1) × (nCols+1)` for later use by the routing module (M6).
7. Puts the new `Grid` into an in-memory `ConcurrentHashMap` keyed by `cityId`.

**`getGrid`** — O(1) ConcurrentHashMap lookup. Throws `IllegalArgumentException` if the city is unknown (not yet initialized).

**`checkServiceability`** — looks up `PincodeMapping` by city + pincode; returns serviceable flag and mapped tileId. Returns `serviceable=false` (not an error) if the pincode is unknown.

**`getTileAt`** — maps a (lat, lon) to a (row, col) via `floor((lat − originLat) / tileDeltaLat)` and queries the tile table. Throws if the computed cell is outside the grid.

### Key design notes
- Grid is immutable after initialization. Tile `active` flags are the only thing that changes (station manager can deactivate tiles).
- The `@PostConstruct` `loadGridCache()` pre-warms the cache at startup from all persisted `Grid` rows.
- Tile size is hardcoded at 2 km — changing it requires re-running `initializeGrid` for all cities.

### Dependencies
- `GridRepository`, `TileRepository`, `PincodeMappingRepository`, `GridVertexRepository`
- `ResourceLoader` (classpath YAML)

---

## 2. DemandScoringService / DemandScoringServiceImpl

**File:** `service/DemandScoringService.java` · `service/impl/DemandScoringServiceImpl.java`

### Contract

```
List<TileDemandSnapshot> computeAndPersistDemand(UUID cityId, LocalDate date)
```

### What it does

Runs nightly (triggered by the nightly replan job) to compute how many minutes of DA work each active tile requires for the upcoming shift. The result is stored as a `TileDemandSnapshot` per tile.

**Demand formula:**
```
demandOrders    = 0.70 × histAvgOrders  +  0.30 × currentOrders    (70/30 blend)
orderEngagedMin = serviceTimeMin        +  interStopTravelMin
demandMinutes   = demandOrders × orderEngagedMin
```

**Data sources (all from M4's `shipment_leg_events` table, last 7 days):**

| Signal | Query | Bootstrap default |
|--------|-------|-------------------|
| `serviceTimeMin` | Avg `(pickup_completed_at − arrived_at_pickup)` per tile; requires ≥ 20 pickups | `12 min` |
| `interStopTravelMin` | Avg consecutive same-DA same-tile stop gap, winsorised at `traversal_cap_sec` | `5 min` |
| `currentOrders` | Order count on the requested date | `0` |
| `histAvgOrders` | Daily avg over the 7 days before the requested date | `0` |

**Bootstrap mode:** If M4 data is entirely absent (pre-launch), city-wide averages substitute for missing per-tile values. The `isBootstrapped` flag on each snapshot records which tiles used estimated values — the assignment solvers use this to flag proposals.

### Key design notes
- Tolerates M4 being unavailable: all four `load*` helpers catch exceptions and return `Map.of()`.
- The 70/30 weighting is a system-wide design invariant (see `CLAUDE.md`).
- `EntityManager.createNativeQuery` is used because these queries cross module boundaries (M4 tables) and there are no JPA entities for them.

### Dependencies
- `GridService` (to get the grid and enumerate active tiles)
- `TileRepository`, `TileDemandSnapshotRepository`
- `EntityManager` (raw SQL into M4 tables)
- `GridProperties` (bootstrap defaults, min-pickup thresholds)

---

## 3. AssignmentService

**File:** `service/AssignmentService.java`

### Contract

```
AssignmentProposal computeProposal(
    UUID cityId, LocalDate validForDate,
    List<TileDemandSnapshot> demand,
    Map<UUID, List<UUID>> adjacencyGraph,
    List<UUID> availableDaIds)
```

A single method that, given demand snapshots and the road-adjacency graph, assigns every tile to a DA and persists the result as an `AssignmentProposal` (with linked `AssignmentProposalRegion` and `DaTileAssignment` rows).

There are two implementations selected via `@Qualifier`:

---

### 3.1 BfsAssignmentServiceImpl

**Qualifier:** `bfsAssignmentService`  
**File:** `service/impl/BfsAssignmentServiceImpl.java`

#### Algorithm

A greedy region-growing algorithm. For each DA in order:
1. Pick the highest-demand unassigned tile as the **seed**.
2. Maintain a max-priority frontier (by demand score). Expand outward, only adding tiles that are road-adjacent to the current territory.
3. Stop when the territory reaches `targetLoad` (70% of shift minutes) or runs out of reachable unassigned tiles.
4. Hard ceiling: skip any tile that would push total load above `maxLoad` (90% of shift minutes).

After all DAs are served, any remaining unassigned tiles become **understaffed tiles** recorded in the proposal.

#### Fallback role
This is the **primary fallback** for CP-SAT. It is called when:
- `demand` or `availableDaIds` is empty
- There are more DAs than tiles (degenerate case)
- CP-SAT fails after retry attempts

It never fails (always produces a proposal, even if coverage < 100%).

#### Key design notes
- No global optimality guarantee — it is a greedy first-fit algorithm.
- Contiguity is enforced structurally: a tile can only join a territory if it has a neighbor already in that territory.
- `adjacencyGraph` can be empty (OSRM unavailable); in that case, contiguity checks are skipped and any tile can be added to any territory.
- Produces `SolverType.BFS_FALLBACK` proposals; `optimalityGapPct` is `null`.

#### Dependencies
- `AssignmentProposalRepository`, `AssignmentProposalRegionRepository`, `DaTileAssignmentRepository`
- `GridProperties` (shift hours, target/max utilisation)

---

### 3.2 CpSatAssignmentServiceImpl

**Qualifier:** `cpSatAssignmentService`  
**File:** `service/impl/CpSatAssignmentServiceImpl.java`

#### Algorithm

Uses **Google OR-Tools CP-SAT** (constraint programming / SAT hybrid solver) to find a globally near-optimal assignment.

**Decision variables:**
- `b[i][k]` — boolean: tile `i` assigned to DA `k`

**Constraints:**
- `addExactlyOne`: every tile belongs to exactly one DA
- `addLinearConstraint(load[k], scaledLb, scaledUb)`: each DA's load must be within `±loadTolerance` of `daTargetLoad`

**Objective:**
- Minimize `maxLoad − minLoad` (flatten the spread between busiest and lightest DA)

**Symmetry breaking:**
- The top-K highest-demand tiles are pre-assigned as seeds (one per DA) to eliminate permutation-equivalent solutions, dramatically reducing search space.

**Contiguity (lazy-cut loop, up to 10 rounds):**
CP-SAT has no built-in geographic constraint. Contiguity is enforced iteratively:
1. Solve the model.
2. Extract territories and check each one for connected components via BFS (`ContiguityValidator.findConnectedComponents`).
3. For any disconnected island, add a linear cut: at most `|island| − 1` of the island's tiles can be assigned to DA `k`. The island containing the seed tile is always kept.
4. Re-solve. Repeat until all territories are connected or 10 rounds are exhausted.

**Infeasibility retries (up to 3):**
If the model is infeasible (load tolerance window too tight), `loadTolerance` is widened by `+0.05` per retry. Falls back to BFS if all retries fail.

**Demand scaling:**
Demand scores (floating-point minutes) are multiplied by `×100` and rounded to longs because CP-SAT only works with integers.

#### Output
Persists:
- `AssignmentProposal` with `SolverType.CP_SAT`, `optimalityGapPct` = `(objValue − bestBound) / objValue × 100`
- `AssignmentProposalRegion` per DA (estimated load, utilisation %)
- `DaTileAssignment` rows

#### Dependencies
- `BfsAssignmentServiceImpl` (fallback)
- `AssignmentProposalRepository`, `AssignmentProposalRegionRepository`, `DaTileAssignmentRepository`
- `GridProperties`
- Google OR-Tools native library (loaded via `Loader.loadNativeLibraries()` in a static block)

---

## 4. ProposalService / ProposalServiceImpl

**File:** `service/ProposalService.java` · `service/impl/ProposalServiceImpl.java`

### Contract

```
ProposalResponse              getProposal(UUID proposalId)
List<ProposalResponse>        getProposals(UUID cityId, LocalDate date)
void                          approve(UUID proposalId, UUID reviewerId)
void                          reject(UUID proposalId, UUID reviewerId, String notes)
void                          editRegionInProposal(UUID proposalId, UUID daId, List<UUID> newTileIds, UUID reviewerId)
IntradayReassignmentResponse  requestIntradayReassignment(UUID cityId, UUID fromDaId, UUID toDaId, List<UUID> tileIdsToMove, UUID requestedBy)
void                          approveIntradayReassignment(UUID proposalId, UUID reviewerId)
TileShareResponse             requestTileShare(UUID cityId, UUID daId, UUID tileId, UUID requestedBy)
void                          approveTileShare(UUID proposalId, UUID reviewerId)
```

### What it does

Manages the full lifecycle of assignment proposals through three distinct scenarios:

#### Scenario 0 — Nightly proposal lifecycle (Reads + approve/reject)

- **`getProposal` / `getProposals`**: Read proposals with their regions and non-superseded tile lists.
- **`approve`**: Transitions a `PROPOSED` proposal to `APPROVED`. Supersedes any previously-approved proposal (and its assignments) for the same city+date. Activates all `DaTileAssignment` rows (sets `APPROVED` status, records `approvedBy` + `approvedAt`).
- **`reject`**: Transitions to `REJECTED` with notes. Does not touch assignment rows.

#### Scenario A — Pre-approval region edit (`editRegionInProposal`)

Allows a station manager to adjust a specific DA's tile set before the proposal is approved:
1. Validates the new tile set is contiguous (using `ContiguityValidator.isConnected`).
2. Supersedes the DA's existing `PROPOSED` assignments under that proposal.
3. Inserts replacement `DaTileAssignment` rows for the new tile set.

No new proposal is created — the edit happens in-place within the existing `PROPOSED` proposal.

#### Scenario B — Intraday tile reassignment (`requestIntradayReassignment` + `approveIntradayReassignment`)

Moves tiles from one DA to another on an already-active plan:
1. Validates all tiles to move are currently `ACTIVE` under `fromDaId`.
2. Checks that `fromDaId`'s remaining territory (after removal) stays connected.
3. Checks that `toDaId`'s expanded territory (after addition) is connected.
4. Creates a new `INTRADAY_OVERRIDE` proposal (type `ProposalType.INTRADAY_OVERRIDE`, solver `MANUAL`) containing the full new tile sets for both affected DAs.
5. `approveIntradayReassignment` supersedes the current `ACTIVE`/`APPROVED` assignments for both DAs and activates the override's rows.

The original active assignments are never mutated — they are only status-transitioned to `SUPERSEDED` (append-only audit trail).

#### Tile share (`requestTileShare` + `approveTileShare`)

Adds a second DA to a tile without removing the existing DA:
1. Validates at least one `ACTIVE` assignment exists for the tile.
2. Creates an `INTRADAY_SHARE` proposal with a single `DaTileAssignment` row (`nDasOnTile = existing + 1`).
3. `approveTileShare` activates that row.

### Key design notes
- All write operations are `@Transactional`.
- The `loadAdjacencyGraph` helper re-reads `TileTravelTime` rows (within `adjacencyThresholdSeconds`) each time it's called — no in-memory cache. This is acceptable since it's only called on manual station-manager actions.
- `toResponse` is eager: it queries regions and assignments per proposal for every response. Consider pagination for cities with many proposals.

### Dependencies
- `AssignmentProposalRepository`, `AssignmentProposalRegionRepository`, `DaTileAssignmentRepository`
- `GridRepository`, `TileTravelTimeRepository`
- `GridProperties` (adjacency threshold)
- `ContiguityValidator` (static utility)

---

## 5. OsrmMatrixService / OsrmMatrixServiceImpl

**File:** `service/OsrmMatrixService.java` · `service/impl/OsrmMatrixServiceImpl.java`

### Contract

```
Map<UUID, List<TileEdge>> computeAdjacencyMatrix(UUID cityId)
Map<UUID, Integer>        computeTraversalCaps(UUID cityId)
```

### What it does

Wraps the OSRM (Open Source Routing Machine) HTTP API to produce two road-time datasets used by the assignment and demand-scoring pipelines.

**`computeAdjacencyMatrix`:**
1. Loads all active tiles for the city.
2. Computes tile centroids: `(originLat + (row + 0.5) × tileDeltaLat, ...)`.
3. Calls `OsrmClient.getTable(centroids)` — a single `/table/v1/driving/...` request returning an N×N duration matrix.
4. For each pair (i, j), adds a `TileEdge(toTileId, durationSeconds)` if `0 < duration ≤ adjacencyThresholdSeconds` (default 600 s / 10 min).
5. Logs any tile with zero reachable neighbors as `ISOLATION_WARNING` — it will get its own DA.

The returned map is passed directly to the assignment solvers. If OSRM is unavailable, the callers receive an empty map and fall back to geometric adjacency.

**`computeTraversalCaps`:**
1. For each active tile, calls `OsrmClient.getTileTraversalCap(swLat, swLon, neLat, neLon)` — a single `/route/v1/driving/` request for the SW→NE diagonal.
2. Returns `tileId → cap in seconds`. Tiles for which OSRM returns no route are omitted.

This is called by the nightly `OsrmMatrixRefreshJob` to populate `tile.traversal_cap_sec`, which `DemandScoringServiceImpl` uses to winsorise inter-stop travel times.

### Key design notes
- `OsrmClient` is not a Spring bean — `OsrmMatrixServiceImpl` constructs it in its constructor from `GridProperties.osrm.baseUrl`. This makes it easy to replace in tests.
- No retry logic. If OSRM fails, the exception propagates and the job should be retried by the scheduler.
- `computeAdjacencyMatrix` makes one large N×N OSRM request, not N individual requests. For very large cities this may hit OSRM's default max-table-size limit.

### Dependencies
- `GridService` (to get the grid)
- `TileRepository`
- `GridProperties` (OSRM base URL, adjacency threshold)
- `OsrmClient` (non-bean HTTP wrapper)

---

## 6. IntradayLoadScoreService / IntradayLoadScoreServiceImpl

**File:** `service/IntradayLoadScoreService.java` · `service/impl/IntradayLoadScoreServiceImpl.java`

### Contract

```
TileLoadScoreResponse getLoadScore(UUID tileId, LocalDate date)
void updateQueueDepth(UUID cityId, LocalDate date, Map<UUID, Integer> unservedByTile)
```

### What it does

Maintains an in-memory, real-time snapshot of unserved order counts per tile during the active shift. Used by station managers and the dispatch module to detect overloaded tiles.

**`updateQueueDepth`:** Called by the `TileQueueDepthConsumer` Kafka consumer on every inbound event. Performs a bulk `putAll` into a `ConcurrentHashMap<UUID, Integer>`. This is a **last-write-wins** model — each event fully replaces the previous depth for the affected tiles.

**`getLoadScore`:** Reads the current unserved count for a tile. Returns a `TileLoadScoreResponse` with:
- `unservedCount` — raw count
- `adjustedLoadScore` — currently equal to raw count (placeholder until M4 provides "expected orders by now" data)
- `severity` — `"OK"` / `"WARNING"` / `"CRITICAL"` based on `GridProperties.intraday` thresholds (default: ≥1.5 = WARNING, ≥2.0 = CRITICAL)

**`resetForShift`:** Package-private method called by `IntradayMonitorJob` at shift start (07:00) to zero out the previous day's data.

### Key design notes
- Entirely in-memory — no database writes. If the service restarts mid-shift, the queue-depth history is lost until Kafka re-delivers events.
- The `date` parameter in both methods is accepted for interface symmetry but not yet used internally (the map is not date-keyed).
- `adjustedLoadScore` is a deliberate stub; it will be enriched with M4 expected-demand data.

### Dependencies
- `GridProperties` (intraday thresholds)
- Kafka consumer (`TileQueueDepthConsumer`, not in this module's service layer)

---

## 7. Supporting Utilities

### 7.1 ContiguityValidator

**File:** `service/impl/ContiguityValidator.java`

Package-private utility class (not a Spring bean) shared by `BfsAssignmentServiceImpl`, `CpSatAssignmentServiceImpl`, and `ProposalServiceImpl`.

| Method | Description |
|--------|-------------|
| `isConnected(tileIds, adjacencyGraph)` | BFS from the first tile; returns `true` if all tiles are reachable. Single-tile sets are trivially connected. |
| `findConnectedComponents(tileIds, adjacencyGraph)` | BFS to enumerate all components; returns them sorted largest-first by tile count. Used by CP-SAT's lazy-cut loop. |

Both methods operate on the road-adjacency graph (tile UUIDs → neighbor tile UUIDs). A geometric-fallback graph (empty map) will cause `isConnected` to always return `false` for multi-tile sets, since no neighbors are reachable.

---

### 7.2 OsrmClient

**File:** `service/osrm/OsrmClient.java`

Not a Spring bean. Constructed directly by `OsrmMatrixServiceImpl`.

| Method | OSRM endpoint | Description |
|--------|--------------|-------------|
| `getTable(latLons)` | `GET /table/v1/driving/{coords}?annotations=duration` | Returns N×N duration matrix in seconds. Expects `lon,lat` ordering (OSRM convention). Throws `RuntimeException` on non-"Ok" response. |
| `getTileTraversalCap(swLat, swLon, neLat, neLon)` | `GET /route/v1/driving/{coords}?overview=false` | Returns driving time (seconds) for SW→NE diagonal of a tile. Returns `null` on OSRM error or unreachable route. |

Uses `RestTemplate` for HTTP and Jackson for JSON parsing.

**`TileEdge`** (`service/osrm/TileEdge.java`) — a simple record: `(UUID toTileId, int travelTimeSec)`. Represents a directed edge in the adjacency graph.

---

### 7.3 GridProperties

**File:** `config/GridProperties.java`

`@ConfigurationProperties(prefix = "grid")` — all values are configurable via `application.yml`.

| Group | Key | Default | Description |
|-------|-----|---------|-------------|
| `osrm` | `baseUrl` | `http://localhost:5000` | OSRM server base URL |
| `osrm` | `adjacencyThresholdSeconds` | `600` | Max road-travel time (s) to consider two tiles adjacent |
| `solver` | `timeLimitSeconds` | `60` | CP-SAT wall-clock time limit per solve attempt |
| `solver` | `loadTolerance` | `0.30` | Acceptable ±30% deviation from DA target load |
| `solver` | `minInterStopPairsPerWindow` | `5` | Min consecutive-stop pairs needed before using per-tile inter-stop time |
| `bootstrap` | `serviceTimeMin` | `12.0` | City-wide default service time (min) when M4 data is unavailable |
| `bootstrap` | `interStopTravelMin` | `5.0` | City-wide default inter-stop travel (min) when M4 data is unavailable |
| `bootstrap` | `minPickupsForRealData` | `20` | Min pickups per tile in 7-day window before switching from bootstrap |
| `shift` | `startHour` | `7` | DA shift start (24h) |
| `shift` | `endHour` | `20` | DA shift end (24h) — gives 780 min / shift |
| `da` | `targetUtilisation` | `0.70` | Target order-engaged fraction of shift (70%) |
| `da` | `maxUtilisation` | `0.90` | Hard ceiling on order-engaged fraction (90%) |
| `intraday` | `overloadWarningThreshold` | `1.5` | Unserved score that triggers WARNING severity |
| `intraday` | `overloadCriticalThreshold` | `2.0` | Unserved score that triggers CRITICAL severity |
| `intraday` | `warningSustainedMinutes` | `15` | Minutes warning must be sustained before alerting |
| `intraday` | `criticalSustainedMinutes` | `10` | Minutes critical must be sustained before alerting |
| `intraday` | `reAlertSuppressionMinutes` | `30` | Cooldown between repeated alerts for the same tile |

---

## Service Dependency Graph

```
GridService
  └── DemandScoringService
        └── AssignmentService (BFS / CP-SAT)
              └── ProposalService

OsrmMatrixService  ──→  GridService
                   ──→  TileRepository

IntradayLoadScoreService  (standalone, Kafka-driven)

ContiguityValidator  ──→  used by BFS, CP-SAT, ProposalService
OsrmClient           ──→  used by OsrmMatrixServiceImpl only
```

## Nightly Replan Flow

```
OsrmMatrixRefreshJob
  1. OsrmMatrixService.computeAdjacencyMatrix(cityId)   → persist TileTravelTime rows
  2. OsrmMatrixService.computeTraversalCaps(cityId)     → update tile.traversal_cap_sec

NightlyReplanJob
  3. DemandScoringService.computeAndPersistDemand(cityId, tomorrow)
  4. AssignmentService.computeProposal(cityId, tomorrow, demand, adjacencyGraph, daIds)
     → tries CP-SAT first, falls back to BFS

Station Manager Review
  5. ProposalService.approve(proposalId, reviewerId)
     or
  5a. ProposalService.editRegionInProposal(...)  →  then approve
```
