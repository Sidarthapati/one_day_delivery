# M3 — Grid Module: Implementation Plan

| Field | Value |
|-------|-------|
| Module | `grid` (M3) |
| Status | **Phases 1–8 + H3 Refactor (Phases A–I) complete. Phase 9 (integration tests) is next.** |
| Stack | Java 21, Spring Boot 3.2, PostgreSQL, Kafka, OR-Tools 9.9, OSRM (self-hosted), h3-java 4.1.1 |
| Depends on | `common` only (no M4/M5 at compile time — consumed via DB reads and Kafka) |

---

## Guiding principles for this implementation

- **Append-only tables**: `da_hex_assignment`, `assignment_proposal`, `assignment_proposal_region`,
  `h3_hex_vertex` — never issue UPDATE or DELETE on these. Only `h3_hex_travel_time` is replace-in-place.
- **Interface-first services**: every service class has a public interface in `service/`. Implementations
  are package-private. CP-SAT and BFS impls both implement `AssignmentService`.
- **No live OSRM at replan time**: the nightly job reads `h3_hex_travel_time` from the DB. OSRM is only
  called in `OsrmMatrixRefreshJob` and `GridInitializationJob`.
- **Bootstrap mode from day one**: all demand scoring paths handle the is-bootstrapped flag. The solver
  runs even without M4 GPS data.
- **No PostGIS**: all geometry is handled by h3-java (GPS → H3 cell index, centroid extraction, vertex extraction).

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

**Original rectangular tile migrations (V1–V10) — left on disk, tables inert:**

| File | Creates (legacy, unused) |
|------|---------|
| `V1__create_grid.sql` | `grid` table (origin_lat/lon/delta) |
| `V2__create_tile.sql` | `tile` table (row_idx/col_idx) |
| `V3__create_tile_travel_time.sql` | `tile_travel_time` table |
| `V4__create_pincode_mapping.sql` | `pincode_mapping` table (tile_id FK) |
| `V5__create_grid_vertex.sql` | `grid_vertex` table (row_idx/col_idx) |
| `V6__create_tile_demand_snapshot.sql` | `tile_demand_snapshot` table |
| `V7__create_assignment_proposal.sql` | `assignment_proposal` table |
| `V8__create_assignment_proposal_region.sql` | `assignment_proposal_region` |
| `V9__create_da_tile_assignment.sql` | `da_tile_assignment` (old, tile FK) |
| `V10__fix_da_tile_assignment_unique.sql` | UNIQUE(proposal_id, da_id, tile_id, valid_date) |

**H3 migrations (V11–V18) — active:**

| File | Creates |
|------|---------|
| `V11__create_h3_grid.sql` | `h3_grid` — `h3_resolution INT` replaces origin/delta |
| `V12__create_h3_hex.sql` | `h3_hex` — `h3_index BIGINT` replaces row_idx/col_idx |
| `V13__create_h3_hex_travel_time.sql` | `h3_hex_travel_time` — FKs to h3_hex |
| `V14__create_h3_pincode_mapping.sql` | `h3_pincode_mapping` — `hex_id` FK |
| `V15__create_h3_hex_vertex.sql` | `h3_hex_vertex` — `h3_vertex_index BIGINT` |
| `V16__create_h3_hex_demand_snapshot.sql` | `h3_hex_demand_snapshot` — `hex_id` FK |
| `V17__create_da_hex_assignment.sql` | `da_hex_assignment` — `hex_id` FK, reuses `assignment_proposal` |
| `V18__rename_understaffed_tile_ids.sql` | Rename `understaffed_tile_ids` → `understaffed_hex_ids` in `assignment_proposal` |

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

| Class | Table | Key fields | Notes |
|-------|-------|-----------|-------|
| `Grid` | `h3_grid` | `cityId, h3Resolution` | Immutable after create |
| `Hex` | `h3_hex` | `h3GridId, h3Index (long), active, traversalCapSec` | `traversalCapSec` nullable |
| `HexTravelTime` | `h3_hex_travel_time` | `h3GridId, fromHexId, toHexId, travelTimeSeconds, computedAt` | Replaced not appended |
| `PincodeMapping` | `h3_pincode_mapping` | `cityId, pincode, hexId, isServiceable` | Serviceability lookup |
| `HexVertex` | `h3_hex_vertex` | `h3GridId, h3VertexIndex (long), lat, lon` | Pre-computed for M6 |
| `HexDemandSnapshot` | `h3_hex_demand_snapshot` | `hexId, snapshotDate, histAvgOrders, currentOrders, demandScoreOrders, serviceTimeMin, interStopTravelMin, orderEngagedMin, demandScoreMinutes, isBootstrapped` | One row per hex per day |
| `AssignmentProposal` | `assignment_proposal` | `cityId, validForDate, status, solverType, adjacencySource, optimalityGapPct, totalDas, coveragePct, understaffedHexIds (JSONB), proposedAt, reviewedBy, reviewedAt, notes` | Append-only; reused from original design |
| `AssignmentProposalRegion` | `assignment_proposal_region` | `proposalId, daId, nDasRequired, estimatedDemandMin, estimatedUtilPct, hasBootstrappedTiles` | Append-only; reused |
| `DaHexAssignment` | `da_hex_assignment` | `proposalId, daId, hexId, validDate, nDasOnHex, status, proposedAt, approvedBy, approvedAt` | Append-only |

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
| `HexRepository` | `findByH3GridIdAndActiveTrue(UUID)`, `findByH3GridIdAndH3Index(UUID, long)` |
| `HexTravelTimeRepository` | `deleteByH3GridId(UUID)` (for OSRM refresh) |
| `PincodeMappingRepository` | `findByCityIdAndPincode(UUID, String)` |
| `HexVertexRepository` | `findByH3GridId(UUID)` |
| `HexDemandSnapshotRepository` | `findByHexIdAndSnapshotDate(UUID, LocalDate)` |
| `AssignmentProposalRepository` | `findByCityIdAndValidForDate(UUID, LocalDate)`, `findByCityIdAndStatusOrderByProposedAtDesc(UUID, ProposalStatus)` |
| `AssignmentProposalRegionRepository` | `findByProposalId(UUID)`, `findByProposalIdAndDaId(UUID, UUID)` |
| `DaHexAssignmentRepository` | `findByProposalId(UUID)`, `findByDaIdAndValidDate(UUID, LocalDate)`, `findByHexIdAndValidDateAndStatus(UUID, LocalDate, AssignmentStatus)`, `findByHexIdInAndValidDateAndStatus(...)` |

---

## Phase 4 — DTOs

Package: `com.oneday.grid.dto`

| Class | Package | Used by |
|-------|---------|---------|
| `ServiceabilityResponse` | response/ | GET /api/grid/{cityCode}/serviceability |
| `TileAtResponse` | response/ | GET /api/grid/{cityCode}/tile-at |
| `TileDetailResponse` | response/ | GET /api/grid/{cityCode}/tiles — includes lat/lng bounds + demand score |
| `AssignmentResponse` | response/ | GET /api/grid/{cityCode}/assignments |
| `DaAssignmentResponse` | response/ | M5 query pattern |
| `GridVertexResponse` | response/ | GET /api/grid/{cityCode}/vertices |
| `TileLoadScoreResponse` | response/ | GET /api/grid/{cityCode}/tiles/{id}/load-score |
| `ProposalResponse` | response/ | GET /api/proposals and GET /api/proposals/{id} |
| `RegionResponse` | response/ | nested in ProposalResponse |
| `IntradayReassignmentResponse` | response/ | POST /api/proposals/intraday-reassignment |
| `TileShareResponse` | response/ | POST /api/proposals/tile-share |
| `ApproveRequest` | request/ | POST /api/proposals/{id}/approve and approve-* variants |
| `ProposalRejectRequest` | request/ | POST /api/proposals/{id}/reject |
| `RegionEditRequest` | request/ | PUT /api/proposals/{id}/regions/{daId} |
| `IntradayReassignmentRequest` | request/ | POST /api/proposals/intraday-reassignment |
| `TileShareRequest` | request/ | POST /api/proposals/tile-share |
| `ReplanRequest` | request/ | POST /api/grid/{cityCode}/replan |

All DTOs are Java records. Sub-packages: `dto/request/` and `dto/response/`.

---

## Phase 5 — Service Layer

Package: `com.oneday.grid.service`

This is the core — implement in this order:

### Step 5.1 — GridService (tile math + pincode mapping)

```java
public interface GridService {
    ServiceabilityResponse checkServiceability(UUID cityId, String pincode);
    TileAtResponse getTileAt(UUID cityId, double lat, double lon);
    void initializeGrid(UUID cityId, String cityCode);
    Grid getGrid(UUID cityId);
    UUID resolveCityId(String cityCode);                               // cityCode → UUID from grid.cities config
    List<TileDetailResponse> getTileDetails(UUID cityId, LocalDate);   // all tiles with lat/lng bounds + demand
    List<GridVertexResponse> getVertices(UUID cityId);                 // all vertices for map grid-lines
    void setHexActive(UUID hexId, boolean active);                     // map UI hex toggle
    List<AssignmentResponse> getActiveAssignments(UUID cityId, LocalDate); // city-scoped ACTIVE assignments
}
```

`GridServiceImpl`:
- On startup (`@PostConstruct`), loads all `Grid` rows into a `Map<UUID, Grid>` (in-memory cache).
- `resolveCityId`: reads from `grid.cities` config map (e.g., `"delhi"` → fixed UUID). Returns 404 if unknown.
- `getTileDetails`: loads all hexes for the city, joins with `HexDemandSnapshot` for the requested date (one batch query), computes centroid lat/lon via H3 `cellToLatLng`.
- `getActiveAssignments`: loads city hex IDs, queries `DaHexAssignmentRepository.findByHexIdInAndValidDateAndStatus(...)` — avoids cross-city contamination.

### Step 5.2 — OsrmMatrixService

```java
public interface OsrmMatrixService {
    // Returns map: fromHexId → list of (toHexId, travelTimeSec) within threshold
    Map<UUID, List<TileEdge>> computeAdjacencyMatrix(UUID cityId);
}
```

`OsrmMatrixServiceImpl` (package-private):
- Loads active hex centroids for city (via H3 `cellToLatLng`).
- POST to `{osrm.base-url}/table/v1/driving/{coords}` (OSRM table API format: lon,lat pairs).
- Parses `durations[][]` matrix.
- Filters pairs `<= adjacency-threshold-seconds`.
- Returns as a list of edges.
- Also computes `traversal_cap_sec` for each hex (H3 `edgeLengthAvg` at the grid resolution).

Implement a simple `OsrmClient` HTTP helper using `RestTemplate` or `WebClient`. Keep it in
`service/osrm/` as a package-private helper — not exposed as a Spring bean.

### Step 5.3 — DemandScoringService

```java
public interface DemandScoringService {
    // Computes and persists HexDemandSnapshot for all active hexes in city for given date
    List<HexDemandSnapshot> computeAndPersistDemand(UUID cityId, LocalDate date);
}
```

`DemandScoringServiceImpl` (package-private):
- Queries M4 tables directly (shipment_leg_events) — this is a cross-module DB read, acceptable
  since M4 and grid share the same PostgreSQL instance in v1.
- Apply bootstrap rules: if < 20 pickups → use city-wide service time average.
- Apply inter-stop guards: winsorise at `traversal_cap_sec`, fallback if < 5 pairs in window.
- Write `HexDemandSnapshot` rows.

**Important**: these SQL queries are in M3-GRID-DESIGN.md §8 steps 3a and 3b. Copy them verbatim into
named native queries on the repository.

### Step 5.4 — AssignmentService (BFS fallback — implement first)

```java
public interface AssignmentService {
    AssignmentProposal computeProposal(UUID cityId, LocalDate validForDate,
                                       List<HexDemandSnapshot> demand,
                                       Map<UUID, List<UUID>> adjacencyGraph,
                                       List<UUID> availableDaIds);
}
```

Implement `BfsAssignmentServiceImpl` first — it is simpler and serves as the integration test baseline.
BFS algorithm:
1. Sort hexes by `demandScoreMinutes` descending.
2. Greedily assign hexes to DAs via BFS from highest-demand seed, respecting `DA_max_load` hard ceiling.
3. Guarantee contiguity by only BFS-expanding into road-adjacent hexes.
4. Return `AssignmentProposal` with `solverType = BFS_FALLBACK`.

### Step 5.5 — AssignmentService (CP-SAT — implement second)

`CpSatAssignmentServiceImpl` (package-private):

Follow the formulation in M3-GRID-DESIGN.md §5.4 Component B exactly:

```
Variables:     assignment[i] ∈ {0, K-1}  one per active hex
Constraints:   load-balance per DA (min ≤ Σdemand ≤ max)
               symmetry-breaking: assignment[seed_hex_k] == k
Objective:     minimize (max_load - min_load) over all DAs
Contiguity:    lazy-cuts loop (BFS per territory, add cut on disconnected sub-assignment, re-solve)
```

OR-Tools key calls:
```java
CpModel model = new CpModel();
IntVar[] assignment = new IntVar[nHexes];
for (int i = 0; i < nHexes; i++)
    assignment[i] = model.newIntVar(0, K - 1, "a_" + hexIds.get(i));

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
    void editRegionInProposal(UUID proposalId, UUID daId, List<UUID> newHexIds, UUID reviewerId);

    // Scenario B: station manager reassigns hexes on an already-ACTIVE plan (intraday)
    ProposalDto requestIntradayReassignment(UUID cityId, UUID fromDaId, UUID toDaId,
                                            List<UUID> hexIdsToMove, UUID requestedBy);
    void approveIntradayReassignment(UUID proposalId, UUID reviewerId);

    // Hex share: add a second DA to a hex without removing the existing DA
    TileShareResponse requestTileShare(UUID cityId, UUID daId, UUID hexId, UUID requestedBy);
    void approveTileShare(UUID proposalId, UUID reviewerId);
}
```

`ProposalServiceImpl` (package-private):
- `approve`: set `status = APPROVED`, set all linked `DaHexAssignment.status = APPROVED`,
  SUPERSEDE previous ACTIVE assignments for same city + date.
- `reject`: set `status = REJECTED`. Do not touch `DaHexAssignment` rows.
- `editRegionInProposal`: validate hex set contiguity for the DA (BFS), validate remaining
  hexes for affected neighbors, write new `DaHexAssignment` rows with the same `proposal_id`.
- `requestIntradayReassignment`:
  1. Validate that hexes to move are currently ACTIVE and assigned to `fromDaId`.
  2. BFS-validate contiguity: `fromDaId` territory minus the moved hexes must still be connected.
  3. BFS-validate: `toDaId` territory plus the moved hexes must be connected (uses road-adjacency
     graph — the moved hexes must be road-adjacent to at least one of `toDaId`'s current hexes).
  4. Create a new `AssignmentProposal` with `proposalType = INTRADAY_OVERRIDE, status = PROPOSED`.
  5. Write new `DaHexAssignment` rows (status = PROPOSED) for both affected DAs' full new
     hex sets — not just the moved hexes.
  6. Return the new proposal so the caller can immediately show "approve this?" in the UI.
  7. If not approved within 10 minutes, the override request expires (status = SUPERSEDED).
- `approveIntradayReassignment`:
  1. Set override proposal `status = APPROVED`.
  2. Set new `DaHexAssignment` rows for the override to `status = ACTIVE`.
  3. Set the old `DaHexAssignment` rows for both affected DAs to `status = SUPERSEDED`.
  4. Log audit event: `{overrideProposalId, fromDaId, toDaId, hexesMoved, reviewedBy, at}`.
- Auto-fallback at 07:00: implemented as a check inside `NightlyReplanJob` — if no APPROVED
  proposal exists for today by 07:00, copy yesterday's ACTIVE assignments.

### Step 5.7 — IntradayLoadScoreService

```java
public interface IntradayLoadScoreService {
    TileLoadScoreResponse getLoadScore(UUID hexId, LocalDate date);
    void updateQueueDepth(UUID cityId, LocalDate date, Map<UUID, Integer> unservedByHex);
}
```

Backed entirely by a `ConcurrentHashMap<UUID, Integer>` (hex_id → unserved_orders).
No DB hit on reads. Map is zeroed at shift-start by `IntradayMonitorJob`.

### Step 5.8 — GridReplanService (replan logic, shared by job and API)

```java
public interface GridReplanService {
    ProposalResponse replan(UUID cityId, LocalDate validForDate, List<UUID> daIds);
}
```

`GridReplanServiceImpl`: extracted from `NightlyReplanJob.replanForCity`. Both `NightlyReplanJob` (which gets `daIds` from `DaRosterPort`) and `GridController` (`POST /api/grid/{cityCode}/replan`) delegate here.

Sequence: demand scoring → load adjacency graph (geometric fallback if OSRM absent/stale) → CP-SAT solver (BFS fallback if infeasible/timeout) → return `ProposalResponse`. Does **not** trigger OSRM refresh — that is the monthly `OsrmMatrixRefreshJob`'s responsibility.

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
3. Insert `h3_grid`, `h3_hex`, and `h3_hex_vertex` rows.
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

Three scheduled methods: `run()` at 01:00, `checkEscalation()` at 06:00, `applyFallbackIfNeeded()` at 07:00.

`run()` iterates all cities. Per city, it gets DA IDs from `DaRosterPort` and delegates:

```java
private void replanForCity(UUID cityId, LocalDate validForDate) {
    List<UUID> daIds = daRosterPort.getAvailableDaIds(cityId, validForDate);
    gridReplanService.replan(cityId, validForDate, daIds);  // ← all solver logic lives here
}
```

The replan logic (demand scoring, adjacency graph, CP-SAT/BFS, proposal persistence) lives in `GridReplanService` — shared with the `POST /api/grid/{cityCode}/replan` API endpoint.

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

## Phase 7 — Kafka Events ✓ DONE

Package: `com.oneday.grid.events`

### KafkaTopics constants

`KafkaTopics.java` is the single source of truth for all topic names:

| Constant | Topic string | Direction |
|----------|-------------|-----------|
| `NO_DA_ALERT` | `grid.no_da_alert` | M3 → M5, M11 |
| `TILE_OVERLOAD_ALERT` | `grid.tile_overload_alert` | M3 → M5, M10 |
| `TILE_QUEUE_DEPTH` | `orders.tile_queue_depth` | M4 → M3 |

### Event POJOs (`events/payload/`)

| Record | Direction | Fields |
|--------|-----------|--------|
| `TileQueueDepthEvent` | inbound (M4 → M3) | `tileId, cityId, date, unservedOrders, bookedOrders, recordedAt` |
| `NoDaAlertEvent` | outbound (M3 → M5/M11) | `cityId, tileId, validDate, reason, alertedAt` |
| `TileOverloadAlertEvent` | outbound (M3 → M5/M10) | `cityId, tileId, daId, date, severity, expectedOrders, unservedOrders, adjustedLoadScore, sustainedMinutes, alertedAt` |

### Step 7.1 — TileQueueDepthConsumer

`@KafkaListener` on topic `orders.tile_queue_depth`, group `grid-service`. **`autoStartup = false`** until M4 ships (flip `grid.kafka.consumer.auto-startup: true` in `application.yml` to enable). On each message calls `loadScoreService.updateQueueDepth(event.cityId(), event.date(), Map.of(event.tileId(), event.unservedOrders()))`.

### Step 7.2 — NoDaAlertProducer

Injects `KafkaTemplate<String, Object>`. Sends `NoDaAlertEvent` to `grid.no_da_alert` keyed by `tileId`. `try/catch` around send — if broker unavailable, logs WARN (app continues without Kafka).

### Step 7.3 — TileOverloadAlertProducer

Same pattern — sends `TileOverloadAlertEvent` to `grid.tile_overload_alert` keyed by `tileId`.

### Kafka config (`application.yml`)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      group-id: grid-service
      auto-offset-reset: latest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.oneday.grid.events.payload"

grid:
  kafka:
    consumer:
      auto-startup: false   # flip to true when M4 is publishing
```

No Kafka broker needed for local dev — producers catch send failures silently, consumer never starts.

---

## Phase 8 — REST API ✓ DONE

Package: `com.oneday.grid.api`. Swagger UI auto-generated at `http://localhost:8080/swagger-ui.html` (springdoc-openapi 2.3.0).

All paths use `{cityCode}` (e.g. `delhi`) which is resolved to a UUID via `GridService.resolveCityId`. City UUIDs are fixed in `grid.cities` config in `application.yml`.

### GridController — `GET|PATCH|POST /api/grid/...`

| Method | Path | Handler | Notes |
|--------|------|---------|-------|
| GET | `/api/grid/{cityCode}/tiles?date=` | `gridService.getTileDetails(cityId, date)` | lat/lng bounds + demand — feeds map UI |
| GET | `/api/grid/{cityCode}/vertices` | `gridService.getVertices(cityId)` | grid-line drawing |
| GET | `/api/grid/{cityCode}/assignments?date=` | `gridService.getActiveAssignments(cityId, date)` | DA territory coloring |
| GET | `/api/grid/{cityCode}/serviceability?pincode=` | `gridService.checkServiceability(cityId, pincode)` | |
| GET | `/api/grid/{cityCode}/tile-at?lat=&lon=` | `gridService.getTileAt(cityId, lat, lon)` | |
| GET | `/api/grid/{cityCode}/tiles/{tileId}/load-score` | `loadScoreService.getLoadScore(tileId, date)` | intraday load |
| PATCH | `/api/grid/{cityCode}/tiles/{tileId}/active?active=` | `gridService.setTileActive(tileId, active)` | map UI tile toggle → 204 |
| POST | `/api/grid/{cityCode}/replan` | `gridReplanService.replan(cityId, date, daIds)` | body: `ReplanRequest` → 201 |
| POST | `/api/grid/admin/init?cityCode=` | `gridService.initializeGrid(cityId, cityCode)` | one-time city setup → 201 |

### ProposalController — `GET|POST|PUT /api/proposals/...`

| Method | Path | Handler | Scenario |
|--------|------|---------|---------|
| GET | `/api/proposals/{proposalId}` | `proposalService.getProposal(id)` | |
| GET | `/api/proposals?cityCode=&date=` | `proposalService.getProposals(cityId, date)` | |
| POST | `/api/proposals/{proposalId}/approve` | `proposalService.approve(id, reviewerId)` | nightly |
| POST | `/api/proposals/{proposalId}/reject` | `proposalService.reject(id, reviewerId, notes)` | nightly |
| PUT | `/api/proposals/{proposalId}/regions/{daId}` | `proposalService.editRegionInProposal(...)` | Scenario A (pre-approval edit) |
| POST | `/api/proposals/intraday-reassignment` | `proposalService.requestIntradayReassignment(...)` | Scenario B → 201 |
| POST | `/api/proposals/{proposalId}/approve-reassignment` | `proposalService.approveIntradayReassignment(...)` | Scenario B |
| POST | `/api/proposals/tile-share` | `proposalService.requestTileShare(...)` | Tile share → 201 |
| POST | `/api/proposals/{proposalId}/approve-tile-share` | `proposalService.approveTileShare(...)` | Tile share |

All approve endpoints take body `{ "reviewerId": "uuid" }` (`ApproveRequest` record). All mutating endpoints return 204 (except POST creates which return 201 with body).

---

## Phase 9 — Testing

### Unit tests (no Spring context)

| Test class | What it tests |
|-----------|--------------|
| `BfsAssignmentServiceImplTest` | BFS produces contiguous, load-balanced territories on a small synthetic H3 grid |
| `CpSatAssignmentServiceImplTest` | CP-SAT produces valid contiguous partition; lazy-cuts converge in ≤5 rounds |
| `DemandScoringServiceImplTest` | Bootstrap rules fire correctly when pickup count < 20; city-wide average fallback |
| `IntradayMonitorJobTest` | `adjustedLoadScore` formula; hysteresis counter; re-alert suppression |
| `ContiguityValidatorTest` | Connected subgraph detection; disconnected territory correctly flagged |
| `ProposalServiceImplTest` | Move hexes between DAs; contiguity validated on both source and destination; expired override not applied |

### Integration tests (TestContainers + real PostgreSQL)

| Test class | What it tests |
|-----------|--------------|
| `GridInitializationIT` | Full city init from YAML → `h3_grid`, `h3_hex`, `h3_hex_vertex` rows correct |
| `NightlyReplanIT` | End-to-end: demand snapshot → proposal → `da_hex_assignment` rows persisted |
| `ProposalApprovalIT` | Approve flow: PROPOSED → APPROVED, previous ACTIVE superseded |
| `OsrmMatrixRefreshIT` | Refresh deletes old `h3_hex_travel_time` rows, inserts new ones; isolation warning logged |

### OSRM mock

For integration tests, stub OSRM with a `WireMock` server returning a fixed travel-time matrix.
Do not test against a live OSRM instance in CI.

---

## Implementation Order Summary

```
Phase 1  ✓  pom.xml + Flyway migrations (V1–V10 legacy + V11–V18 H3) + config + YAML stubs
Phase 2  ✓  9 JPA entities + 5 enums
Phase 3  ✓  9 repository interfaces
Phase 4  ✓  17 DTO records (request/ + response/ subpackages)
Phase 5  ✓  Services: GridService, OsrmMatrixService, DemandScoringService,
              BfsAssignmentServiceImpl, CpSatAssignmentServiceImpl, ProposalService,
              IntradayLoadScoreService, GridReplanService
           ✓  65 unit tests, 0 failures
Phase 6  ✓  Batch jobs: GridInitializationJob, OsrmMatrixRefreshJob,
              NightlyReplanJob (delegates to GridReplanService), IntradayMonitorJob
Phase 7  ✓  Kafka: TileQueueDepthConsumer (autoStartup=false), NoDaAlertProducer,
              HexOverloadAlertProducer, KafkaTopics constants, 3 event POJOs
Phase 8  ✓  REST: GridController (9 endpoints), ProposalController (8 endpoints)
              Swagger UI at /swagger-ui.html

H3 Refactor (Phases A–I) ✓
           A  h3-java 4.1.1 dep + H3Config bean + Flyway V11–V17
           B  Domain: Grid(h3Resolution), Hex, HexVertex, HexTravelTime,
                HexDemandSnapshot, DaHexAssignment entities
           C  Repositories: Hex/HexVertex/HexTravelTime/HexDemandSnapshot/DaHexAssignment
           D  GridService: initializeGrid (polyfill), getTileAt (h3Index), getTileDetails (centroids)
           E  OsrmMatrixService: centroid via cellToLatLng, traversal cap via edgeLengthAvg
           F  CP-SAT + BFS seed selection: lat/lon centroids replace row/col
           G  DTOs + API: h3Index string, centerLat/centerLon, hexId
           H  All 108 unit tests updated and green
           I  Config cleanup (removed centerLat from city YAMLs, h3.resolution in application.yml)
              Code cleanup: 10 old Tile/GridVertex entity + repo files deleted;
              understaffedHexIds (renamed column + V18 migration); TileEdge.toHexId renamed

Phase 9  ○  Integration tests (TestContainers + real PostgreSQL) — not started
```

**Next up:** Phase 9 integration tests.

---

## Cross-Module Contracts (agree before coding these paths)

| Contract | With module | What to align on |
|----------|-------------|-----------------|
| `dispatch.tile_queue_depth` Kafka topic | M5 | Exact JSON schema (§16.2.1); 5-minute cadence confirmed |
| `shipment_leg_events` table read | M4 | Column names (`da_id`, `shift_date`, `stop_sequence`, `arrived_at_pickup`, `pickup_completed_at`, `hex_id`); must exist before DemandScoringService can run |
| `grid.no_da_alert` | M10 | M10 needs this to be wired into its SLA consumer before end-to-end testing |
| DA IDs in `da_hex_assignment` | M1/auth | `da_id` is a UUID from M1's `users` table — no FK constraint in M3 schema (avoids cross-module DB coupling), but document the implicit reference |

---

## Known risks

| Risk | Mitigation |
|------|-----------|
| OR-Tools native lib not found on dev Mac | `ortools-java` 9.9.x bundles macOS arm64 native libs — should work out of the box; if not, add `<classifier>` for platform |
| CP-SAT solver slow on large city (>200 tiles) | Time limit is 60s; lazy-cuts adds overhead per round. Profile on a synthetic 200-tile grid before go-live. Reduce `LOAD_TOLERANCE` if model is too loose (fewer cuts needed) |
| M4 `shipment_leg_events` not yet implemented | DemandScoringService will use bootstrap defaults universally — this is correct behavior. No code changes needed |
| OSRM unreachable in local dev | Use geometric fallback (`GEOMETRIC_FALLBACK`) — all code paths handle this |
| Proposal not approved before 07:00 | Auto-fallback path must be smoke-tested in integration tests; use a test clock to advance time |

---

## What M3 Needs From Other Modules (and doesn't have yet)

M3 is fully built but several of its code paths are running in degraded / stub mode because the modules it depends on haven't shipped yet. This section documents every dependency, what currently happens without it, and what needs to happen when each module lands.

---

### From M1 — Auth / Identity

#### 1. DA roster (`DaRosterPort`)

| Item | Detail |
|------|--------|
| Interface | `DaRosterPort.getAvailableDaIds(UUID cityId, LocalDate date)` |
| Used by | `NightlyReplanJob` — to know how many DAs are available per city each morning |
| Current state | `NoOpDaRosterPort` always returns an empty list. The nightly job runs, demand scoring happens, but the solver receives zero DAs → all tiles flagged understaffed → no assignment proposal is useful |
| What to do when M1 ships | Write a real `M1DaRosterPortImpl` that queries M1's `users` table for users with role `DA` and city = cityId and shift date = date. Annotate it `@Primary` to override `NoOpDaRosterPort`. |
| File | `grid/src/main/java/com/oneday/grid/batch/DaRosterPort.java` + new impl |

#### 2. DA UUIDs (implicit FK)

| Item | Detail |
|------|--------|
| Where used | `da_hex_assignment.da_id` column |
| Current state | The demo and tests use randomly generated UUIDs. No DB-level FK constraint exists (by design — avoids cross-module coupling), but in production these must be valid user UUIDs with DA role from M1. |
| Risk | If a DA UUID in an assignment has no corresponding M1 user, M5 won't be able to look up the DA's contact info, app login, or shift schedule. |
| What to do when M1 ships | Validate DA UUIDs in `ProposalServiceImpl` before writing `DaHexAssignment` rows: reject UUIDs that don't exist in M1. |

---

### From M4 — Orders

#### 3. Shipment leg events table (demand scoring)

| Item | Detail |
|------|--------|
| Table | `shipment_leg_events` |
| Columns needed | `da_id`, `shift_date`, `stop_sequence`, `arrived_at_pickup`, `pickup_completed_at`, `hex_id` |
| Used by | `DemandScoringServiceImpl` — to compute per-tile service time, inter-stop travel time, historical avg orders, and current day orders |
| Current state | Table does not exist. All M4DataLoader calls fail silently (caught by `safeCall` → returns empty map). The service falls back to bootstrap defaults: all tiles get `serviceTimeMin = 12.0`, `interStopTravelMin = 5.0`, `histAvgOrders = 0`, `currentOrders = 0` → `demandScoreMinutes = 0` for every tile. The CP-SAT solver still runs but produces arbitrary equal-load territories since all tiles look identical. |
| Impact | Territories are not demand-weighted until M4 ships. The 70/30 demand model cannot activate. |
| What to do when M4 ships | Flip `grid.bootstrap.min-pickups-for-real-data` down from 20 once there is enough data. No code changes needed — the queries are already written and will start returning data automatically once the table exists with the right columns. Run a backfill for the first 7-day rolling window before enabling. |
| File | `M4DataLoader.java` — all 4 query methods |

#### 4. Tile queue depth (intraday load monitoring)

| Item | Detail |
|------|--------|
| Kafka topic | `orders.tile_queue_depth` |
| Direction | M4 → M3 |
| Event schema | `TileQueueDepthEvent { hexId, cityId, date, unservedOrders, bookedOrders, recordedAt }` |
| Used by | `TileQueueDepthConsumer` → `IntradayLoadScoreServiceImpl` → `IntradayMonitorJob` |
| Current state | `TileQueueDepthConsumer` has `autoStartup = false` in `application.yml`. The consumer never starts. `IntradayMonitorJob` runs but sees 0 unserved orders everywhere → no `HEX_OVERLOAD_ALERT` events are ever fired. |
| Impact | The intraday alerting pipeline is completely silent until M4 ships. M5 and M10 never receive overload alerts. |
| What to do when M4 ships | Flip `grid.kafka.consumer.auto-startup: true` in `application.yml`. No code changes needed. Verify the event schema matches `TileQueueDepthEvent` before enabling. |
| File | `application.yml` — one config change. `TileQueueDepthConsumer.java` already written. |

> **Note — topic name discrepancy:** The implementation uses topic `orders.tile_queue_depth` (M4 → M3). The cross-module contracts section above references `dispatch.tile_queue_depth` with M5. These need to be aligned before M4 ships. Likely resolution: M4 orders module publishes it (`orders.` prefix); M5 dispatch was listed by mistake.

---

### What M3 Produces for Other Modules

| Output | Topic / mechanism | Consumed by | Status |
|--------|------------------|-------------|--------|
| No-DA alert | `grid.no_da_alert` Kafka | M5 (stops accepting pickups for the hex), M11 (flags for call center) | Fires correctly today, but consumers not yet built |
| Hex overload alert | `grid.tile_overload_alert` Kafka | M5 (may trigger intraday reassignment request), M10 (SLA tracking) | Fires correctly today, but consumers not yet built |
| DA hex assignments | `da_hex_assignment` table (DB read) | M5 reads this to know which DA to route each order to | Table populated on proposal approval; M5 must refresh after each approval |
| Assignment updated event | Not yet implemented | M5 needs a push notification when the active plan changes so it doesn't serve stale routing | **Gap:** `grid.assignment_updated` Kafka event should be published from `ProposalServiceImpl.approve()`. Referenced in design docs but not yet coded. |

---

### Dependency Summary

| Dependency | From | Without it | Fix when module ships |
|-----------|------|-----------|----------------------|
| DA roster | M1 | All hexes understaffed, no usable assignments | Real `DaRosterPort` impl annotated `@Primary` |
| DA UUID validation | M1 | Phantom DA UUIDs in assignments | Validate before writing `DaHexAssignment` |
| `shipment_leg_events` | M4 | Bootstrap defaults for all hexes, equal-weight territories | Automatic — queries already written |
| `orders.tile_queue_depth` Kafka | M4 | Intraday alerting silent, no overload events | Flip `auto-startup: true` in config |
| `grid.assignment_updated` event | M3 → M5 | M5 serves stale routing after plan changes | Add Kafka publish in `ProposalServiceImpl.approve()` |
