# M3 — Grid Module: Implementation Plan

| Field | Value |
|-------|-------|
| Module | `grid` (M3) |
| Status | Ready to implement — design locked (M3-GRID-DESIGN.md v1.0) |
| Stack | Java 21, Spring Boot 3.2, PostgreSQL, Kafka, OR-Tools 9.9, OSRM (self-hosted) |
| Depends on | `common` only (no M4/M5 at compile time — consumed via DB reads and Kafka) |

---

## Guiding principles for this implementation

- **Append-only tables**: `da_tile_assignment`, `assignment_proposal`, `assignment_proposal_region`,
  `grid_vertex` — never issue UPDATE or DELETE on these. Only `tile_travel_time` is replace-in-place.
- **Interface-first services**: every service class has a public interface in `service/`. Implementations
  are package-private. CP-SAT and BFS impls both implement `AssignmentService`.
- **No live OSRM at replan time**: the nightly job reads `tile_travel_time` from the DB. OSRM is only
  called in `OsrmMatrixRefreshJob` and `GridInitializationJob`.
- **Bootstrap mode from day one**: all demand scoring paths handle the is-bootstrapped flag. The solver
  runs even without M4 GPS data.
- **No PostGIS**: all geometry is pure arithmetic (integer division for GPS → tile, Δlat/Δlon).

---

## Phase 1 — Foundation (do this first, nothing else compiles without it)

### Step 1.1 — Update `grid/pom.xml`

Add the following dependencies:

```xml
<!-- OR-Tools CP-SAT solver -->
<dependency>
    <groupId>com.google.ortools</groupId>
    <artifactId>ortools-java</artifactId>
    <version>9.9.3963</version>
</dependency>

<!-- Flyway for migrations -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>

<!-- Kafka -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>

<!-- Jackson for OSRM JSON + YAML serviceability configs -->
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>

<!-- Spring Data JPA (already via parent probably, confirm) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- Test -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

### Step 1.2 — Flyway migrations

Create `grid/src/main/resources/db/migration/` with one file per concern.
Order matters — FK references must come after their targets.

| File | Creates |
|------|---------|
| `V1__create_grid.sql` | `grid` table |
| `V2__create_tile.sql` | `tile` table (FK → grid) |
| `V3__create_tile_travel_time.sql` | `tile_travel_time` table (FK → grid, tile) |
| `V4__create_pincode_mapping.sql` | `pincode_mapping` table (FK → tile) |
| `V5__create_grid_vertex.sql` | `grid_vertex` table (FK → grid) |
| `V6__create_tile_demand_snapshot.sql` | `tile_demand_snapshot` table (FK → tile) |
| `V7__create_assignment_proposal.sql` | `assignment_proposal` table |
| `V8__create_assignment_proposal_region.sql` | `assignment_proposal_region` (FK → proposal) |
| `V9__create_da_tile_assignment.sql` | `da_tile_assignment` (FK → proposal, tile) |

Use the exact DDL from M3-GRID-DESIGN.md §6. Key things to check:
- `grid`: UNIQUE(city_id)
- `tile`: UNIQUE(grid_id, row_idx, col_idx); `traversal_cap_sec INT` nullable
- `tile_travel_time`: no long-term append — this is the one replace-in-place table
- `da_tile_assignment`: UNIQUE(da_id, tile_id, valid_date)
- `assignment_proposal_region`: UNIQUE(proposal_id, da_id)

### Step 1.3 — Application config

`grid/src/main/resources/application.yml` (values filled in at deployment, these are the keys):

```yaml
grid:
  osrm:
    base-url: http://localhost:5000
    adjacency-threshold-seconds: 600
  solver:
    time-limit-seconds: 60
    load-tolerance: 0.30
    min-inter-stop-pairs-per-window: 5
  bootstrap:
    service-time-min: 12.0
    inter-stop-travel-min: 5.0
    min-pickups-for-real-data: 20
  shift:
    start-hour: 7
    end-hour: 20
  da:
    target-utilisation: 0.70
    max-utilisation: 0.90
  intraday:
    overload-warning-threshold: 1.5
    overload-critical-threshold: 2.0
    warning-sustained-minutes: 15
    critical-sustained-minutes: 10
    re-alert-suppression-minutes: 30
```

Create a `@ConfigurationProperties(prefix = "grid")` class `GridProperties.java` in `service/` to bind all of this.

### Step 1.4 — Serviceability YAML configs

Create `grid/src/main/resources/serviceability/` with one YAML per city:
`delhi.yaml`, `mumbai.yaml`, `bangalore.yaml`, `hyderabad.yaml`, `chennai.yaml`.

Each follows the schema from M3-GRID-DESIGN.md §4.1. At minimum include the city center lat/lon and a
representative list of serviceable pincodes (can be populated with real data before go-live; a skeleton
with 3–5 pincodes per city is enough for the build to run).

---

## Phase 2 — Domain Layer (JPA entities)

Package: `com.oneday.grid.domain`

Create one file per entity. Each should extend `BaseEntity` from `common` if it provides
`id` (UUID) + `createdAt`. If `BaseEntity` doesn't cover it, define fields explicitly.

| Class | Key fields | Notes |
|-------|-----------|-------|
| `Grid` | `cityId, originLat, originLon, tileDeltaLat, tileDeltaLon` | Immutable after create |
| `Tile` | `gridId, rowIdx, colIdx, isActive, traversalCapSec` | `traversalCapSec` nullable |
| `TileTravelTime` | `gridId, fromTileId, toTileId, travelTimeSeconds, computedAt` | Replaced not appended |
| `PincodeMapping` | `cityId, pincode, tileId, isServiceable` | Serviceability lookup |
| `GridVertex` | `gridId, rowIdx, colIdx, lat, lon` | Pre-computed for M6 |
| `TileDemandSnapshot` | `tileId, snapshotDate, histAvgOrders, currentOrders, demandScoreOrders, serviceTimeMin, interStopTravelMin, orderEngagedMin, demandScoreMinutes, isBootstrapped` | One row per tile per day |
| `AssignmentProposal` | `cityId, validForDate, status, solverType, adjacencySource, optimalityGapPct, totalDas, coveragePct, understaffedTileIds (JSONB), proposedAt, reviewedBy, reviewedAt, notes` | Append-only |
| `AssignmentProposalRegion` | `proposalId, daId, nDasRequired, estimatedDemandMin, estimatedUtilPct, hasBootstrappedTiles` | Append-only |
| `DaTileAssignment` | `proposalId, daId, tileId, validDate, nDasOnTile, status, proposedAt, approvedBy, approvedAt` | Append-only |

**Enums to create:**

```java
// com.oneday.grid.domain
enum ProposalStatus  { PROPOSED, APPROVED, REJECTED, SUPERSEDED }
enum ProposalType    { NIGHTLY, INTRADAY_OVERRIDE, INTRADAY_SUGGESTION }
enum SolverType      { CP_SAT, BFS_FALLBACK, MANUAL }
enum AdjacencySource { OSRM, GEOMETRIC_FALLBACK }
enum AssignmentStatus { PROPOSED, APPROVED, ACTIVE, SUPERSEDED }
```

Add `proposal_type VARCHAR(30) NOT NULL DEFAULT 'NIGHTLY'` to the `assignment_proposal` table
(add to V7 migration). Nightly job writes `NIGHTLY`. Station manager reassignment writes
`INTRADAY_OVERRIDE`. Level 3 auto-suggestion writes `INTRADAY_SUGGESTION`.

---

## Phase 3 — Repository Layer

Package: `com.oneday.grid.repository`

Spring Data JPA interfaces only — no custom SQL unless a named query is genuinely needed.

| Repository | Key custom queries |
|-----------|-------------------|
| `GridRepository` | `findByCityId(UUID)` |
| `TileRepository` | `findByGridIdAndIsActiveTrue(UUID)`, `findByGridIdAndRowIdxAndColIdx(...)` |
| `TileTravelTimeRepository` | `deleteByGridId(UUID)` (for OSRM refresh), `findByGridIdAndTravelTimeSecondsLessThanEqual(UUID, int)` |
| `PincodeMappingRepository` | `findByCityIdAndPincode(UUID, String)` |
| `GridVertexRepository` | `findByGridId(UUID)` |
| `TileDemandSnapshotRepository` | `findByTileIdAndSnapshotDate(UUID, LocalDate)` |
| `AssignmentProposalRepository` | `findByCityIdAndValidForDate(UUID, LocalDate)`, `findByCityIdAndStatusOrderByProposedAtDesc(UUID, ProposalStatus)` |
| `AssignmentProposalRegionRepository` | `findByProposalId(UUID)`, `findByProposalIdAndDaId(UUID, UUID)` |
| `DaTileAssignmentRepository` | `findByProposalId(UUID)`, `findByDaIdAndValidDate(UUID, LocalDate)`, `findByTileIdAndValidDateAndStatus(UUID, LocalDate, AssignmentStatus)` |

---

## Phase 4 — DTOs

Package: `com.oneday.grid.dto`

| Class | Used by |
|-------|---------|
| `ServiceabilityResponse` | GET /grid/serviceability |
| `TileAtResponse` | GET /grid/tile-at |
| `AssignmentResponse` | GET /grid/assignments |
| `DaAssignmentResponse` | GET /grid/assignments/da/{da_id} |
| `GridVertexResponse` | GET /grid/vertices |
| `TileLoadScoreResponse` | GET /grid/tiles/{tile_id}/load-score |
| `ProposalDto` | GET /grid/proposals |
| `RegionDto` | nested in ProposalDto |
| `OverrideRequest` | PUT /grid/proposals/{id}/regions/{id}/override |
| `ProposalRejectRequest` | POST /grid/proposals/{id}/reject (optional notes field) |
| `IntradayReassignmentRequest` | POST /grid/assignments/intraday-override |
| `IntradayReassignmentResponse` | response for the above — includes new proposal_id for tracking |
| `TileShareRequest` | POST /grid/assignments/tile-share |
| `TileShareResponse` | response for the above — includes new proposal_id for approval screen |

Use Java records for all DTOs (Java 21).

---

## Phase 5 — Service Layer

Package: `com.oneday.grid.service`

This is the core — implement in this order:

### Step 5.1 — GridService (tile math + pincode mapping)

```java
public interface GridService {
    ServiceabilityResponse checkServiceability(UUID cityId, String pincode);
    TileAtResponse getTileAt(UUID cityId, double lat, double lon);
    void initializeGrid(UUID cityId);  // called by GridInitializationJob
    Grid getGrid(UUID cityId);         // cached
}
```

`GridServiceImpl` (package-private):
- On startup, load all `Grid` rows into a `Map<UUID, Grid>` (in-memory cache — geometry never changes).
- `getTileAt`: pure arithmetic — `row = floor((lat - originLat) / tileDeltaLat)`, `col = ...`.
- `checkServiceability`: lookup `PincodeMapping` by `(cityId, pincode)` — no computation.
- `initializeGrid`: reads the city's YAML, computes bounding box + tile grid, inserts Grid/Tile/GridVertex rows, triggers OSRM refresh.

### Step 5.2 — OsrmMatrixService

```java
public interface OsrmMatrixService {
    // Returns map: fromTileId → list of (toTileId, travelTimeSec) within threshold
    Map<UUID, List<TileEdge>> computeAdjacencyMatrix(UUID cityId);
}
```

`OsrmMatrixServiceImpl` (package-private):
- Loads active tile centroids for city.
- POST to `{osrm.base-url}/table/v1/driving/{coords}` (OSRM table API format: lon,lat pairs).
- Parses `durations[][]` matrix.
- Filters pairs `<= adjacency-threshold-seconds`.
- Returns as a list of edges.
- Also computes `traversal_cap_sec` for each tile (SW corner → NE corner OSRM call).

Implement a simple `OsrmClient` HTTP helper using `RestTemplate` or `WebClient`. Keep it in
`service/osrm/` as a package-private helper — not exposed as a Spring bean.

### Step 5.3 — DemandScoringService

```java
public interface DemandScoringService {
    // Computes and persists TileDemandSnapshot for all active tiles in city for given date
    List<TileDemandSnapshot> computeAndPersistDemand(UUID cityId, LocalDate date);
}
```

`DemandScoringServiceImpl` (package-private):
- Queries M4 tables directly (shipment_leg_events) — this is a cross-module DB read, acceptable
  since M4 and grid share the same PostgreSQL instance in v1.
- Apply bootstrap rules: if < 20 pickups → use city-wide service time average.
- Apply inter-stop guards: winsorise at `traversal_cap_sec`, fallback if < 5 pairs in window.
- Write `TileDemandSnapshot` rows.

**Important**: these SQL queries are in M3-GRID-DESIGN.md §8 steps 3a and 3b. Copy them verbatim into
named native queries on the repository.

### Step 5.4 — AssignmentService (BFS fallback — implement first)

```java
public interface AssignmentService {
    AssignmentProposal computeProposal(UUID cityId, LocalDate validForDate,
                                       List<TileDemandSnapshot> demand,
                                       Map<UUID, List<UUID>> adjacencyGraph,
                                       List<UUID> availableDaIds);
}
```

Implement `BfsAssignmentServiceImpl` first — it is simpler and serves as the integration test baseline.
BFS algorithm:
1. Sort tiles by `demandScoreMinutes` descending.
2. Greedily assign tiles to DAs via BFS from highest-demand seed, respecting `DA_max_load` hard ceiling.
3. Guarantee contiguity by only BFS-expanding into road-adjacent tiles.
4. Return `AssignmentProposal` with `solverType = BFS_FALLBACK`.

### Step 5.5 — AssignmentService (CP-SAT — implement second)

`CpSatAssignmentServiceImpl` (package-private):

Follow the formulation in M3-GRID-DESIGN.md §5.4 Component B exactly:

```
Variables:     assignment[i] ∈ {0, K-1}  one per active tile
Constraints:   load-balance per DA (min ≤ Σdemand ≤ max)
               symmetry-breaking: assignment[seed_tile_k] == k
Objective:     minimize (max_load - min_load) over all DAs
Contiguity:    lazy-cuts loop (BFS per territory, add cut on disconnected sub-assignment, re-solve)
```

OR-Tools key calls:
```java
CpModel model = new CpModel();
IntVar[] assignment = new IntVar[nTiles];
for (int i = 0; i < nTiles; i++)
    assignment[i] = model.newIntVar(0, K - 1, "a_" + tileIds.get(i));

// Load balance per DA k: lb <= sum(demand[i] * (assignment[i] == k)) <= ub
// Use model.newBoolVar + model.addEquality for indicator vars

CpSolver solver = new CpSolver();
solver.getParameters().setMaxTimeInSeconds(timeLimitSeconds);
CpSolverStatus status = solver.solve(model);
```

The lazy-cuts loop calls `solver.solve(model)` in a while loop, each iteration adding new
`model.addBoolOr(...)` constraints to break disconnected sub-assignments.

When done: return `AssignmentProposal` with `solverType = CP_SAT`,
`optimalityGapPct = solver.objectiveValue() / (solver.bestObjectiveBound() + ε)`.

On timeout or `INFEASIBLE` status:
- If infeasible: widen `LOAD_TOLERANCE` by 5% and retry up to 3× (E11).
- If still infeasible or timeout: delegate to `BfsAssignmentServiceImpl`.

### Step 5.6 — ProposalService

Two distinct override scenarios need separate methods. Do not conflate them.

**Scenario A — pre-approval edit:** The nightly proposal is PROPOSED but not yet approved.
The station manager wants to tweak one DA's region before signing off. This edits within the
existing proposal — no new proposal is created.

**Scenario B — intraday reassignment:** Today's plan is already APPROVED and ACTIVE (e.g., it's
10am). The station manager wants to permanently move a tile from DA-A to DA-B for the rest of
today. This creates a brand-new `INTRADAY_OVERRIDE` proposal that itself needs approval (immediate,
short confirmation window), then supersedes the current ACTIVE assignments for the affected DAs.

```java
public interface ProposalService {
    ProposalDto getProposal(UUID proposalId);
    List<ProposalDto> getProposals(UUID cityId, LocalDate date);

    // Nightly proposal lifecycle
    void approve(UUID proposalId, UUID reviewerId);
    void reject(UUID proposalId, UUID reviewerId, String notes);

    // Scenario A: edit a DA's region inside an existing PROPOSED (pre-approval) proposal
    void editRegionInProposal(UUID proposalId, UUID daId, List<UUID> newTileIds, UUID reviewerId);

    // Scenario B: station manager reassigns tiles on an already-ACTIVE plan (intraday)
    ProposalDto requestIntradayReassignment(UUID cityId, UUID fromDaId, UUID toDaId,
                                            List<UUID> tileIdsToMove, UUID requestedBy);
    void approveIntradayReassignment(UUID proposalId, UUID reviewerId);

    // Tile share: add a second DA to a tile without removing the existing DA
    TileShareResponse requestTileShare(UUID cityId, UUID daId, UUID tileId, UUID requestedBy);
    void approveTileShare(UUID proposalId, UUID reviewerId);
}
```

`ProposalServiceImpl` (package-private):
- `approve`: set `status = APPROVED`, set all linked `DaTileAssignment.status = APPROVED`,
  SUPERSEDE previous ACTIVE assignments for same city + date.
- `reject`: set `status = REJECTED`. Do not touch DaTileAssignment rows.
- `editRegionInProposal`: validate tile set contiguity for the DA (BFS), validate remaining
  tiles for affected neighbors, write new `DaTileAssignment` rows with the same `proposal_id`.
- `requestIntradayReassignment`:
  1. Validate that tiles to move are currently ACTIVE and assigned to `fromDaId`.
  2. BFS-validate contiguity: `fromDaId` territory minus the moved tiles must still be connected.
  3. BFS-validate: `toDaId` territory plus the moved tiles must be connected (uses road-adjacency
     graph — the moved tiles must be road-adjacent to at least one of `toDaId`'s current tiles).
  4. Create a new `AssignmentProposal` with `proposalType = INTRADAY_OVERRIDE, status = PROPOSED`.
  5. Write new `DaTileAssignment` rows (status = PROPOSED) for both affected DAs' full new
     tile sets — not just the moved tiles.
  6. Return the new proposal so the caller can immediately show "approve this?" in the UI.
  7. If not approved within 10 minutes, the override request expires (status = SUPERSEDED).
- `approveIntradayReassignment`:
  1. Set override proposal `status = APPROVED`.
  2. Set new `DaTileAssignment` rows for the override to `status = ACTIVE`.
  3. Set the old `DaTileAssignment` rows for both affected DAs to `status = SUPERSEDED`.
  4. Log audit event: `{overrideProposalId, fromDaId, toDaId, tilesMoved, reviewedBy, at}`.
- Auto-fallback at 07:00: implemented as a check inside `NightlyReplanJob` — if no APPROVED
  proposal exists for today by 07:00, copy yesterday's ACTIVE assignments.

### Step 5.7 — IntradayLoadScoreService

```java
public interface IntradayLoadScoreService {
    TileLoadScoreResponse getLoadScore(UUID tileId, LocalDate date);
    void updateQueueDepth(UUID cityId, LocalDate date, Map<UUID, Integer> unservedByTile);
}
```

Backed entirely by a `ConcurrentHashMap<UUID, Integer>` (tile_id → unserved_orders).
No DB hit on reads. Map is zeroed at shift-start by `IntradayMonitorJob`.

---

## Phase 6 — Batch Jobs

Package: `com.oneday.grid.batch`

Implement in this order (each one tests the services from Phase 5):

### Step 6.1 — GridInitializationJob

```java
@Component
public class GridInitializationJob {
    // Admin-triggered via POST /grid/admin/init?city_id=...
    public void initialize(UUID cityId) { ... }
}
```

Sequence (from M3-GRID-DESIGN.md §10):
1. Load serviceability YAML for city.
2. Compute bounding box + tile geometry.
3. Insert Grid + Tile + GridVertex rows.
4. Insert PincodeMapping rows.
5. Trigger `OsrmMatrixRefreshJob.refresh(cityId)`.

### Step 6.2 — OsrmMatrixRefreshJob

```java
@Component
public class OsrmMatrixRefreshJob {
    // Admin-triggered via POST /grid/admin/osrm-refresh?city_id=...
    // Also runs monthly via @Scheduled
    public void refresh(UUID cityId) { ... }
}
```

Sequence (from §9):
1. Load active tile centroids.
2. Call `OsrmMatrixService.computeAdjacencyMatrix(cityId)`.
3. Delete existing `tile_travel_time` rows for city's grid.
4. Insert new rows for pairs within threshold.
5. Update `tile.traversal_cap_sec` for each tile.
6. Log isolation warnings (tiles with 0 OSRM neighbors).

### Step 6.3 — NightlyReplanJob

```java
@Component
public class NightlyReplanJob {
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Kolkata")
    public void run() { ... }
}
```

Run once per city. Sequence (from §8):
1. Compute demand via `DemandScoringService.computeAndPersistDemand(cityId, tomorrow)`.
2. Load road-adjacency graph from `tile_travel_time` — DB read, not OSRM.
   - If empty or `computedAt > 45 days ago`: trigger OSRM refresh first.
   - If refresh fails: use geometric 4-connectivity with `GEOMETRIC_FALLBACK` flag.
3. Pre-process multi-DA tiles (Component C): split virtual sub-tiles for tiles where
   `demandScoreMinutes > DA_max_load`.
4. Count `K_available` (active DAs for city tomorrow) vs `K_needed`.
5. Run `CpSatAssignmentServiceImpl.computeProposal(...)`.
6. Post-process: collapse virtual sub-tiles, compute per-region stats.
7. Persist `AssignmentProposal` + `AssignmentProposalRegion` + `DaTileAssignment` rows.
8. Send "proposal awaiting approval" notification (Kafka or in-app — TBD with station manager UI team).
9. Check: if it's now past 06:00 and no proposal approved → escalation alert.
10. Check: if it's now past 07:00 and no proposal approved → auto-apply yesterday's assignment.

### Step 6.4 — IntradayMonitorJob

```java
@Component
public class IntradayMonitorJob {
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)
    public void run() { ... }
}
```

Only runs during shift hours (07:00–20:00 local time). Per city, per active tile:
1. Read `unservedOrders` from in-memory map (populated by `TileQueueDepthConsumer`).
2. Compute `adjustedLoadScore = unserved / max(expected_by_now, 1)`.
3. Update `sustainedMinutes[tile]` with hysteresis.
4. If threshold breached and `lastAlertAt > 30 min ago`: emit `grid.tile_overload_alert`.
5. If `CRITICAL` and Level 3 is enabled: run local BFS suggestion.

---

## Phase 7 — Kafka Events

Package: `com.oneday.grid.events`

### Step 7.1 — TileQueueDepthConsumer

```java
@KafkaListener(topics = "dispatch.tile_queue_depth")
public class TileQueueDepthConsumer {
    public void consume(TileQueueDepthEvent event) {
        // Replace in-memory map entirely (last-write wins)
        loadScoreService.updateQueueDepth(event.cityId(), event.operatingDate(), event.tileDepths());
    }
}
```

Deserialize the payload from M3-GRID-DESIGN.md §16.2.1.

### Step 7.2 — NoDaAlertProducer

```java
@Component
public class NoDaAlertProducer {
    // topic: grid.no_da_alert
    public void emit(UUID cityId, UUID tileId, LocalDate validDate, String reason) { ... }
}
```

Payload schema from §7.7.

### Step 7.3 — TileOverloadAlertProducer

```java
@Component
public class TileOverloadAlertProducer {
    // topic: grid.tile_overload_alert
    public void emit(UUID cityId, UUID tileId, UUID daId, LocalDate date,
                     String severity, double expectedOrders, int unserved,
                     double adjustedScore, int sustainedMinutes) { ... }
}
```

Payload schema from §7.9.

---

## Phase 8 — REST API

Package: `com.oneday.grid.api`

### GridController

| Method | Path | Handler |
|--------|------|---------|
| GET | `/grid/serviceability` | `gridService.checkServiceability(cityId, pincode)` |
| GET | `/grid/tile-at` | `gridService.getTileAt(cityId, lat, lon)` |
| GET | `/grid/assignments` | `daTileAssignmentRepo.findByCityIdAndValidDate(...)` |
| GET | `/grid/assignments/da/{daId}` | `daTileAssignmentRepo.findByDaIdAndValidDate(...)` |
| GET | `/grid/vertices` | `gridVertexRepo.findByGridId(...)` |
| GET | `/grid/tiles/{tileId}/load-score` | `loadScoreService.getLoadScore(tileId, today)` |
| POST | `/grid/admin/init` | `gridInitializationJob.initialize(cityId)` |
| POST | `/grid/admin/osrm-refresh` | `osrmMatrixRefreshJob.refresh(cityId)` |

### ProposalController

| Method | Path | Handler | Scenario |
|--------|------|---------|---------|
| GET | `/grid/proposals` | `proposalService.getProposals(cityId, date)` | both |
| GET | `/grid/proposals/{id}` | `proposalService.getProposal(id)` | both |
| POST | `/grid/proposals/{id}/approve` | `proposalService.approve(id, reviewerId)` | nightly |
| POST | `/grid/proposals/{id}/reject` | `proposalService.reject(id, reviewerId, notes)` | nightly |
| PUT | `/grid/proposals/{id}/regions/{regionId}/edit` | `proposalService.editRegionInProposal(...)` | Scenario A |
| POST | `/grid/assignments/intraday-override` | `proposalService.requestIntradayReassignment(...)` | Scenario B |
| POST | `/grid/proposals/{id}/approve-intraday` | `proposalService.approveIntradayReassignment(id, reviewerId)` | Scenario B |
| POST | `/grid/assignments/tile-share` | `proposalService.requestTileShare(...)` | Tile share |
| POST | `/grid/proposals/{id}/approve-tile-share` | `proposalService.approveTileShare(id, reviewerId)` | Tile share |

**Scenario B request body:**
```json
{
  "city_id": "...",
  "from_da_id": "...",
  "to_da_id": "...",
  "tile_ids_to_move": ["...", "..."],
  "requested_by": "..."
}
```

**Scenario B response** includes the new `proposal_id` so the station manager UI can immediately
show a confirmation screen with the contiguity-validated plan and a one-tap approve button.

---

## Phase 9 — Testing

### Unit tests (no Spring context)

| Test class | What it tests |
|-----------|--------------|
| `TileArithmeticTest` | GPS → tile row/col conversion; edge cases (exactly on boundary) |
| `BfsAssignmentServiceTest` | BFS produces contiguous, load-balanced territories on a small synthetic grid |
| `CpSatAssignmentServiceTest` | CP-SAT produces valid contiguous partition; lazy-cuts converge in ≤5 rounds |
| `DemandScoringBootstrapTest` | Bootstrap rules fire correctly when pickup count < 20; city-wide average fallback |
| `IntradayMonitorLogicTest` | `adjustedLoadScore` formula; hysteresis counter; re-alert suppression |
| `ContiguityValidatorTest` | Connected subgraph detection; disconnected territory correctly flagged |
| `IntradayReassignmentTest` | Move tiles between DAs; contiguity validated on both source and destination; expired override not applied |

### Integration tests (TestContainers + real PostgreSQL)

| Test class | What it tests |
|-----------|--------------|
| `GridInitializationIT` | Full city init from YAML → Grid/Tile/GridVertex rows correct |
| `NightlyReplanIT` | End-to-end: demand snapshot → proposal → DaTileAssignment rows persisted |
| `ProposalApprovalIT` | Approve flow: PROPOSED → APPROVED, previous ACTIVE superseded |
| `OsrmMatrixRefreshIT` | Refresh deletes old rows, inserts new ones; isolation warning logged |

### OSRM mock

For integration tests, stub OSRM with a `WireMock` server returning a fixed travel-time matrix.
Do not test against a live OSRM instance in CI.

---

## Implementation Order Summary

```
Phase 1 (foundation)    → pom.xml + Flyway migrations + config + YAML stubs
Phase 2 (domain)        → 9 JPA entities + 4 enums
Phase 3 (repos)         → 9 repository interfaces
Phase 4 (DTOs)          → 10 record classes
Phase 5.1 (GridService) → tile math, pincode lookup, grid cache
Phase 5.2 (OSRM)        → OsrmMatrixService + OsrmClient
Phase 5.3 (demand)      → DemandScoringService + bootstrap rules
Phase 5.4 (BFS)         → BfsAssignmentServiceImpl (fallback)
Phase 5.5 (CP-SAT)      → CpSatAssignmentServiceImpl + lazy-cuts
Phase 5.6 (proposals)   → ProposalService (approve/reject/override)
Phase 5.7 (load scores) → IntradayLoadScoreService
Phase 6.1 (init job)    → GridInitializationJob
Phase 6.2 (OSRM job)    → OsrmMatrixRefreshJob
Phase 6.3 (nightly)     → NightlyReplanJob
Phase 6.4 (intraday)    → IntradayMonitorJob
Phase 7 (Kafka)         → TileQueueDepthConsumer, NoDaAlertProducer, TileOverloadAlertProducer
Phase 8 (API)           → GridController, ProposalController
Phase 9 (tests)         → unit tests → integration tests
```

**Do not start Phase 6 without Phase 5 unit tests passing.** The nightly job is the most complex
integration point — bugs caught at service level are far cheaper to fix than bugs caught in the job.

---

## Cross-Module Contracts (agree before coding these paths)

| Contract | With module | What to align on |
|----------|-------------|-----------------|
| `dispatch.tile_queue_depth` Kafka topic | M5 | Exact JSON schema (§16.2.1); 5-minute cadence confirmed |
| `shipment_leg_events` table read | M4 | Column names (`da_id`, `shift_date`, `stop_sequence`, `arrived_at_pickup`, `pickup_completed_at`, `tile_id`); must exist before DemandScoringService can run |
| `grid.no_da_alert` | M10 | M10 needs this to be wired into its SLA consumer before end-to-end testing |
| DA IDs in `da_tile_assignment` | M1/auth | `da_id` is a UUID from M1's `users` table — no FK constraint in M3 schema (avoids cross-module DB coupling), but document the implicit reference |

---

## Known risks

| Risk | Mitigation |
|------|-----------|
| OR-Tools native lib not found on dev Mac | `ortools-java` 9.9.x bundles macOS arm64 native libs — should work out of the box; if not, add `<classifier>` for platform |
| CP-SAT solver slow on large city (>200 tiles) | Time limit is 60s; lazy-cuts adds overhead per round. Profile on a synthetic 200-tile grid before go-live. Reduce `LOAD_TOLERANCE` if model is too loose (fewer cuts needed) |
| M4 `shipment_leg_events` not yet implemented | DemandScoringService will use bootstrap defaults universally — this is correct behavior. No code changes needed |
| OSRM unreachable in local dev | Use geometric fallback (`GEOMETRIC_FALLBACK`) — all code paths handle this |
| Proposal not approved before 07:00 | Auto-fallback path must be smoke-tested in integration tests; use a test clock to advance time |
