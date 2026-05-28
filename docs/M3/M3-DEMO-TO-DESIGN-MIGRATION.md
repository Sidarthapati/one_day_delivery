# M3 Demo Branch → Design Branch: Completed Migration Record

Branch: `f-M3-demo` → `f-m3-design`

Migration completed 2026-05-28. This document records all logic changes that were ported from the
demo branch to the design branch. Demo-specific code (UI, DemoController, DemoSecurityConfig, seed
SQL, application.yml overrides, delhi.yaml) was intentionally left in the demo branch.

---

## Summary Table

| File | Change Type | Priority | Status |
|------|-------------|----------|--------|
| `CpSatAssignmentServiceImpl` | Complete rework — two-phase solve, geographic seeds, distance penalty, warm-start, BFS repair | High | ✅ Done |
| `BfsAssignmentServiceImpl` | 3 bug fixes — target load, hard ceiling, util% | High | ✅ Done |
| `DemandScoringServiceImpl` | M4DataLoader extraction + snapshot existence check + safeCall | High | ✅ Done |
| `M4DataLoader` (new class) | REQUIRES_NEW transaction isolation for M4 SQL queries | High | ✅ Done |
| `BalancedBfsAssignmentServiceImpl` (new class) | New solver variant: competitive flooding + local swap refinement | Medium | ✅ Done |
| `SolverType` | Add `BALANCED_BFS` enum value | Medium | ✅ Done |
| `AssignmentProposal` | `@JdbcTypeCode(SqlTypes.JSON)` on `understaffedTileIds` | Medium | ✅ Done |
| `DaTileAssignmentRepository` | Two new query methods for multi-status lookups | Medium | ✅ Done |
| `GridProperties` | Solver time limit 60s → 45s | Low | ✅ Done |
| `GridReplanServiceImpl` | Rename field `cpSatAssignmentService` → `assignmentService` | Low (cosmetic) | ✅ Done |

**All 10 items implemented. 108/108 unit tests passing.**

---

## 1. BfsAssignmentServiceImpl — 3 Logic Fixes

### Fix 1: Dynamic target load instead of fixed shift capacity

**Problem:** When total demand > K × shift_capacity (common with real/seed data), using `shiftMin *
targetUtilisation` as the BFS stop threshold caused BFS to stop after 2-3 high-demand tiles per DA,
leaving most tiles unassigned.

**Fix:** Compute `daTargetLoad = totalDemandMinutes / K` (actual average per DA). Fall back to
`daCapacity` only if total demand is zero.

```java
// Before
double daMaxLoad = shiftMin * properties.getDa().getMaxUtilisation();
double daTargetLoad = shiftMin * properties.getDa().getTargetUtilisation();

// After
double daCapacity = shiftMin * properties.getDa().getTargetUtilisation();
double totalDemandMinutes = demandMap.values().stream().mapToDouble(Double::doubleValue).sum();
double daTargetLoad = totalDemandMinutes > 0 ? totalDemandMinutes / K : daCapacity;
```

### Fix 2: Remove hard ceiling check

**Problem:** The hard ceiling `if (!territory.isEmpty() && load + tileLoad > daMaxLoad) continue;`
worked together with the old fixed `daTargetLoad`. With the dynamic target, it caused the same
early-stop problem on high-demand tiles.

**Fix:** Removed the hard ceiling entirely. BFS expands until it hits `daTargetLoad`, then stops.

### Fix 3: Util% uses capacity, not dynamic target

**Problem:** `estimatedUtilPct = totalDemand / daTargetLoad` was dividing by the dynamic average,
giving util% > 1.0 for overloaded DAs and making the metric uninterpretable.

**Fix:** `estimatedUtilPct = totalDemand / daCapacity` (always divide by true shift capacity).

---

## 2. CpSatAssignmentServiceImpl — Complete Rework

The old lazy-cuts contiguity loop is gone. Replaced by a two-phase approach that produces contiguous
territories without the solver running a multi-round loop. See `M3-CPSAT-ISSUE.md` for the full
history of approaches tried before landing on this architecture.

### 2.1 New dependency: `TileRepository`

The solver now needs tile geometry (rowIdx, colIdx) for seed selection and isolated-tile stapling.
`TileRepository tileRepository` was added to the constructor and field.

### 2.2 Isolated tile detection (pre-solve)

Before calling CP-SAT, tiles are split into:
- `connectedDemand`: tiles that appear in the adjacency graph with at least one neighbor
- `isolatedDemand`: tiles with no road neighbors (no edges in the adjacency graph)

CP-SAT only runs on `connectedDemand`. Isolated tiles are handled post-solve (§2.8).

When `adjacencyGraph` is empty (geometric fallback), all tiles go into `connectedDemand`.

### 2.3 Travel overhead in effective demand

Added `INTER_TILE_TRAVEL_MIN = 25.0 min` per tile to effective demand so sprawling territories
are genuinely costlier for the solver.

```java
private static final double INTER_TILE_TRAVEL_MIN = 25.0;
private static final double DEMAND_SCALE = 100.0;
long overheadPerTile = Math.round(INTER_TILE_TRAVEL_MIN * DEMAND_SCALE);
long[] effectiveScaledDemand = new long[nConnected];
for (int i = 0; i < nConnected; i++)
    effectiveScaledDemand[i] = scaledDemand[i] + overheadPerTile;
```

### 2.4 Geographic furthest-first seed selection (replaces top-K demand)

Old: seeds were the K highest-demand tiles — all could be in the same dense area.

New: furthest-first (k-means++ style). Start from the tile nearest the bounding-box center,
then pick each next seed as the tile that maximizes minimum distance to all existing seeds.
Returns a `SeedResult` record carrying `tileRows[]` and `tileCols[]` arrays (reused for the
objective penalty and warm-start).

```java
private record SeedResult(int[] seeds, int[] tileRows, int[] tileCols) {}
private SeedResult computeSeedIndices(List<TileDemandSnapshot> demand, Map<UUID, Tile> tileMap, int K)
```

### 2.5 Distance penalty in objective (replaces lazy-cut contiguity)

Old: hard contiguity was enforced via iterative lazy cuts (up to 10 rounds of re-solve).

New: soft distance penalty added to the objective. For each tile `i` and DA `k`:
`penalty = β × dist(tile_i, seed_k) × b[i][k]` where `β = DIST_PENALTY_SCALE = 700L`.
This discourages assigning distant tiles to a DA, naturally producing compact territories.

```java
private static final long DIST_PENALTY_SCALE = 700L;
var objExpr = LinearExpr.newBuilder().add(maxLoad).addTerm(minLoad, -1L);
for (int i = 0; i < nTiles; i++) {
    for (int k = 0; k < K; k++) {
        double dr = tileRows[i] - seedRows[k];
        double dc = tileCols[i] - seedCols[k];
        long penalty = Math.round(Math.sqrt(dr * dr + dc * dc) * DIST_PENALTY_SCALE);
        if (penalty > 0) objExpr.addTerm(b[i][k], penalty);
    }
}
model.minimize(objExpr);
```

### 2.6 Voronoi warm-start hint

Before calling `solver.solve()`, each tile is hinted to its nearest seed (pure geometric Voronoi).
Gives CP-SAT a good initial feasible solution and cuts wall-clock time.

### 2.7 Phase 2: BFS connectivity repair (`repairConnectivity`)

After CP-SAT returns, the distance penalty greatly reduces disconnected territories but doesn't
guarantee zero. Phase 2 repairs remaining disconnections:

1. BFS from each DA's seed through its current territory. Record which tiles are reachable.
2. Collect tiles in a DA's territory but not reachable from its seed.
3. For each disconnected tile: find a neighbor DA whose entry tile is reachable from that DA's seed.
   Among valid candidates, pick the DA with the most remaining capacity.
4. Repeat until no disconnected tiles remain or no progress is made.

### 2.8 Isolated tile stapling (post-solve)

After the repair step, each isolated tile is attached to the DA whose seed is geographically
nearest (Euclidean distance in row/col space).

### 2.9 estimatedDemandMin includes travel overhead

In `persistProposal`, the region's `estimatedDemandMin` adds `nTiles × INTER_TILE_TRAVEL_MIN`
to the raw demand sum, consistent with how `daTargetLoad` was computed.

### 2.10 Removed constants/methods

- `MAX_LAZY_CUT_ROUNDS = 10` — removed (lazy-cut loop gone)
- Old `computeSeedIndices(long[] scaledDemand, int K)` — replaced by geographic version
- Log format: `"gap={:.1f}%"` → `"gap={}%"` (fix: Java SLF4J uses `{}`, not `{:.1f}`)

---

## 3. DemandScoringServiceImpl — M4DataLoader Extraction

### 3.1 Extract M4 queries to `M4DataLoader`

The 4 private query methods were moved into a new package-private `@Service` class `M4DataLoader`.
Each method carries `@Transactional(propagation = Propagation.REQUIRES_NEW)` — this is the key
fix: when `shipment_leg_events` doesn't exist, the inner transaction rolls back by itself without
corrupting the outer transaction (previously caused `UnexpectedRollbackException`).

`EntityManager entityManager` was replaced with `M4DataLoader m4`. Call sites:

```java
Map<UUID, Double> serviceTimeMins = safeCall(() -> m4.loadServiceTimeMins(properties.getBootstrap().getMinPickupsForRealData()), Map.of());
Map<UUID, Double> interStopTravelMins = safeCall(() -> m4.loadInterStopTravelMins(properties.getSolver().getMinInterStopPairsPerWindow()), Map.of());
Map<UUID, Integer> currentOrders = safeCall(() -> m4.loadCurrentOrders(date), Map.of());
Map<UUID, Double> histAvgOrders = safeCall(() -> m4.loadHistAvgOrders(date), Map.of());
```

### 3.2 Snapshot existence check

At the top of `computeDemandSnapshots`, before any queries run, checks if all active tiles already
have demand snapshots for the requested date. If yes, returns immediately — preserves manual
demand overrides and avoids redundant recomputation on same-day reruns.

```java
Set<UUID> activeTileIds = activeTiles.stream().map(Tile::getId).collect(Collectors.toSet());
Map<UUID, TileDemandSnapshot> existingByTile = snapshotRepository.findBySnapshotDate(date).stream()
        .filter(s -> activeTileIds.contains(s.getTileId()))
        .collect(Collectors.toMap(TileDemandSnapshot::getTileId, s -> s, (a, b) -> b));
if (existingByTile.keySet().containsAll(activeTileIds)) {
    return new ArrayList<>(existingByTile.values());
}
```

The `(a, b) -> b` merge function deduplicates in case of multiple snapshots per tile.

### 3.3 safeCall wrapper

```java
private <T> T safeCall(Supplier<T> fn, T fallback) {
    try { return fn.get(); }
    catch (Exception e) {
        log.debug("M4 data loader failed (treating as unavailable): {}", e.getMessage());
        return fallback;
    }
}
```

---

## 4. M4DataLoader — New Class

New package-private `@Service` in `com.oneday.grid.service.impl`. Wraps 4 native SQL queries that
query M4's `shipment_leg_events` table, each in its own `REQUIRES_NEW` transaction.

```java
@Service
class M4DataLoader {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Map<UUID, Double> loadServiceTimeMins(int minPickups) { ... }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Map<UUID, Double> loadInterStopTravelMins(int minPairs) { ... }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Map<UUID, Integer> loadCurrentOrders(LocalDate date) { ... }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Map<UUID, Double> loadHistAvgOrders(LocalDate date) { ... }
}
```

SQL bodies are identical to what was in `DemandScoringServiceImpl` before.

---

## 5. BalancedBfsAssignmentServiceImpl — New Class

`@Service` (`@Qualifier("balancedBfsAssignmentService")`). A third solver variant between plain
BFS and CP-SAT:

**Algorithm:**
1. Competitive flooding — all K seeds expand simultaneously (via a `PriorityQueue` ordered by
   remaining capacity); the DA with the most remaining capacity expands first each round.
2. Boundary swap refinement — up to `MAX_SWAP_PASSES = 300` passes where border tiles are moved
   to adjacent DA territories if the swap reduces load imbalance. Donor connectivity is verified
   via BFS from seed before each swap is accepted.

Seed selection uses BFS graph-distance furthest-first (not geometric Euclidean like CP-SAT).

The `GridReplanServiceImpl` `@Qualifier` still points at CP-SAT as the primary solver. This class
is available but not yet wired in as default.

---

## 6. Supporting Changes

### `SolverType.java`

```java
public enum SolverType { CP_SAT, BFS_FALLBACK, BALANCED_BFS, MANUAL }
```

### `AssignmentProposal.java`

Hibernate JSON type hint to avoid serialization warnings on the JSONB column:

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "understaffed_tile_ids", columnDefinition = "jsonb")
private String understaffedTileIds;
```

Imports added: `org.hibernate.annotations.JdbcTypeCode`, `org.hibernate.type.SqlTypes`.

### `DaTileAssignmentRepository.java`

```java
List<DaTileAssignment> findByTileIdAndValidDateAndStatusIn(
        UUID tileId, LocalDate validDate, Collection<AssignmentStatus> statuses);

List<DaTileAssignment> findByTileIdInAndValidDateAndStatusIn(
        Collection<UUID> tileIds, LocalDate validDate, Collection<AssignmentStatus> statuses);
```

### `GridProperties.java`

Solver time limit: `timeLimitSeconds = 60` → `timeLimitSeconds = 45`.

### `GridReplanServiceImpl.java`

Cosmetic rename: field `cpSatAssignmentService` → `assignmentService`. No behavior change.

---

## 7. Test Changes Required During Migration

Three test files required updates to compile and pass after the logic changes:

### 7.1 `CpSatAssignmentServiceImplTest` — new `TileRepository` dependency

**Error:** Constructor mismatch — `CpSatAssignmentServiceImpl` gained a `TileRepository` param.

**Fix:**
- Added `@Mock TileRepository tileRepository;` field
- Added `import com.oneday.grid.repository.TileRepository;`
- Updated constructor call to include `tileRepository` as the 4th arg

### 7.2 `DemandScoringServiceImplTest` — `EntityManager` → `M4DataLoader` mock

**Error:** `incompatible types: EntityManager cannot be converted to M4DataLoader`

**Fix:** Complete test rewrite. Replaced `@Mock EntityManager entityManager` with
`@Mock M4DataLoader m4`. Removed `Query`/`jakarta.persistence.EntityManager` imports and the
`queryReturning()`/`stubQueries()` helpers. Added `stubM4()`:

```java
private void stubM4(Map<UUID, Double> svcData, Map<UUID, Double> interData,
                    Map<UUID, Integer> currentData, Map<UUID, Double> histData) {
    lenient().when(m4.loadServiceTimeMins(anyInt())).thenReturn(svcData);
    lenient().when(m4.loadInterStopTravelMins(anyInt())).thenReturn(interData);
    lenient().when(m4.loadCurrentOrders(date)).thenReturn(currentData);
    lenient().when(m4.loadHistAvgOrders(date)).thenReturn(histData);
}
```

The `noActiveTiles` test no longer stubs M4 (early return from snapshot check prevents those
calls). The `queryThrows` test now stubs M4 methods to throw directly instead of stubbing
`EntityManager` query chains.

### 7.3 `BfsAssignmentServiceImplTest` — test was asserting a bug, not correct behavior

**Error:** `Expected size: 1 but was: 2` — test `secondTileExceedsMaxLoad_tileIsUnderstaffed`
was asserting that the old hard-ceiling behavior prevented a second tile from being assigned when
load would exceed `daMaxLoad`. The hard ceiling was intentionally removed as a bug fix.

**Fix:** Renamed test to `singleDa_twoadjacentTiles_bothAssigned`. Updated assertions to
expect both tiles assigned and `coveragePct = 100.0` (correct post-fix behavior).

---

## What Was NOT Ported (Demo-Only)

| File | Reason |
|------|--------|
| `DemoController.java` | Demo endpoint only |
| `DemoSecurityConfig.java` | Open-access security config for demo |
| `WebConfig.java` | CORS config for demo UI |
| `GridApplication.java` changes | Demo runner wiring |
| `DemoTileDemandRequest.java` | Demo DTO |
| `seed_delhi_demand.sql` | Demo seed data |
| `application.yml` changes | Demo DB URL / cors overrides |
| `delhi.yaml` changes | Demo serviceability config tweaks |
| `demo-ui/` | Entire React frontend |

### Tests still only in `f-M3-demo`

| Test | Status |
|------|--------|
| `BalancedBfsConvergenceTest.java` | Integration/convergence test for `BalancedBfsAssignmentServiceImpl`. Port separately when Phase 9 integration tests are added. |
| `CpSatLazyCutConvergenceTest.java` | Tests the old lazy-cut architecture that was removed. No longer relevant; do not port. |
| `GridControllerTest.java` updates | Demo-specific controller test changes. |
| `GridTestApplication.java` updates | Demo test runner changes. |
