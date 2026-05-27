# M3 Demo Branch → Design Branch: Logic Changes to Port

Branch: `f-M3-demo` → `f-m3-design`

This document lists only raw logic changes from the demo branch that need to be ported to the
design branch. Demo-specific code (UI, DemoController, DemoSecurityConfig, seed SQL, application.yml
overrides, delhi.yaml) is intentionally excluded.

---

## Summary Table

| File | Change Type | Priority |
|------|-------------|----------|
| `CpSatAssignmentServiceImpl` | Complete rework — two-phase solve, geographic seeds, distance penalty, warm-start, BFS repair | High |
| `BfsAssignmentServiceImpl` | 3 bug fixes — target load, hard ceiling, util% | High |
| `DemandScoringServiceImpl` | M4DataLoader extraction + snapshot existence check + safeCall | High |
| `M4DataLoader` (new class) | REQUIRES_NEW transaction isolation for M4 SQL queries | High |
| `BalancedBfsAssignmentServiceImpl` (new class) | New solver variant: BFS init + local swap refinement | Medium |
| `SolverType` | Add `BALANCED_BFS` enum value | Medium |
| `AssignmentProposal` | `@JdbcTypeCode(SqlTypes.JSON)` on `understaffedTileIds` | Medium |
| `DaTileAssignmentRepository` | Two new query methods for multi-status lookups | Medium |
| `GridProperties` | Solver time limit 60s → 45s | Low |
| `GridReplanServiceImpl` | Rename field `cpSatAssignmentService` → `assignmentService` | Low (cosmetic) |

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
worked together with the old fixed `daTargetLoad`. With the dynamic target, it causes the same
early-stop problem on high-demand tiles.

**Fix:** Remove the hard ceiling entirely. BFS expands until it hits `daTargetLoad`, then stops.

### Fix 3: Util% uses capacity, not dynamic target

**Problem:** `estimatedUtilPct = totalDemand / daTargetLoad` was dividing by the dynamic average,
giving util% > 1.0 for overloaded DAs and making the metric uninterpretable.

**Fix:** `estimatedUtilPct = totalDemand / daCapacity` (always divide by true shift capacity).

---

## 2. CpSatAssignmentServiceImpl — Complete Rework

This is the largest change. The old lazy-cuts contiguity loop is gone. Replaced by a two-phase
approach that produces contiguous territories without the solver running a multi-round loop.

### 2.1 New dependency: `TileRepository`

The solver now needs tile geometry (rowIdx, colIdx) for seed selection and isolated-tile stapling.
Add `TileRepository tileRepository` to constructor and field.

### 2.2 Isolated tile detection (pre-solve)

Before calling CP-SAT, split `demand` into two lists:
- `connectedDemand`: tiles that appear in the adjacency graph with at least one neighbor
- `isolatedDemand`: tiles with no road neighbors (no edges in the adjacency graph)

CP-SAT only runs on `connectedDemand`. Isolated tiles are handled post-solve (see §2.5).

```java
connectedDemand = demand.stream()
        .filter(d -> !adjacencyGraph.getOrDefault(d.getTileId(), List.of()).isEmpty())
        .toList();
isolatedDemand = demand.stream()
        .filter(d -> adjacencyGraph.getOrDefault(d.getTileId(), List.of()).isEmpty())
        .toList();
```

When `adjacencyGraph` is empty (geometric fallback), all tiles go into `connectedDemand`.

### 2.3 Travel overhead in effective demand

Each tile added to a territory implies inter-tile travel. Add a constant overhead per tile
(`INTER_TILE_TRAVEL_MIN = 25.0 min`) to effective demand so that sprawling territories
are genuinely costlier for the solver.

```java
private static final double INTER_TILE_TRAVEL_MIN = 25.0;
private static final double DEMAND_SCALE = 100.0;
long overheadPerTile = Math.round(INTER_TILE_TRAVEL_MIN * DEMAND_SCALE);
long[] effectiveScaledDemand = new long[nConnected];
for (int i = 0; i < nConnected; i++)
    effectiveScaledDemand[i] = scaledDemand[i] + overheadPerTile;
```

`daTargetLoad` also shifts to include overhead: `effectiveTotalDemand / K` where
`effectiveTotalDemand = totalDemandMinutes + nConnected × INTER_TILE_TRAVEL_MIN`.

### 2.4 New seed selection: furthest-first geographic (replaces top-K demand)

Old: seeds were the K highest-demand tiles — all could be in the same dense area.

New: furthest-first (k-means++ style). Start from the tile nearest the bounding-box center,
then pick each next seed as the tile that maximizes minimum distance to all existing seeds.
Returns a `SeedResult` record that also carries `tileRows[]` and `tileCols[]` arrays (reused
for the objective penalty and warm-start).

```java
private record SeedResult(int[] seeds, int[] tileRows, int[] tileCols) {}
private SeedResult computeSeedIndices(List<TileDemandSnapshot> demand, Map<UUID, Tile> tileMap, int K)
```

### 2.5 Distance penalty in objective (replaces lazy-cut contiguity)

Old: hard contiguity was enforced via iterative lazy cuts (up to 10 rounds of re-solve).

New: soft distance penalty added to the objective. For each tile `i` and DA `k`:
`penalty = β × dist(tile_i, seed_k) × b[i][k]` where `β = DIST_PENALTY_SCALE = 700L` (scaled
as demand units). This discourages assigning distant tiles to a DA, naturally producing compact
territories without any flow constraint or re-solve loop.

```java
private static final long DIST_PENALTY_SCALE = 700L;
// In trySolve():
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

Before calling `solver.solve()`, hint each tile to its nearest seed (pure geometric Voronoi).
This gives CP-SAT a good initial feasible solution and cuts wall-clock time.

```java
for (int i = 0; i < nTiles; i++) {
    int nearestK = /* argmin over k of dist(tile_i, seed_k) */;
    for (int k = 0; k < K; k++) model.addHint(b[i][k], k == nearestK ? 1 : 0);
}
```

### 2.7 Phase 2: BFS connectivity repair (new method `repairConnectivity`)

After CP-SAT returns, the distance penalty greatly reduces disconnected territories but doesn't
guarantee zero. Phase 2 repairs any remaining disconnections.

**Algorithm:**
1. BFS from each DA's seed through tiles currently in its territory. Record which tiles are
   reachable (`reachable[k][t]`).
2. Collect all tiles that are in a DA's territory but not reachable from its seed.
3. For each disconnected tile `t` (currently in DA `fromDA`): find a neighbor DA `j` such that
   the neighbor tile through which `t` connects to `j` is itself reachable from `j`'s seed.
   Among valid candidate DAs, pick the one with the most remaining capacity.
4. Repeat until no disconnected tiles remain or no progress is made (stuck island).

This guarantees the reassignment produces a provably connected territory, not just shifts the
disconnection somewhere else.

```java
private Map<Integer, List<Integer>> repairConnectivity(
        Map<Integer, List<Integer>> territories, List<UUID> tileIds,
        Map<UUID, List<UUID>> adjacencyGraph, Map<UUID, Integer> tileIndexMap,
        int[] seedIndices, long[] scaledDemand, long daCapacityScaled, int nTiles, int K)
```

### 2.8 Isolated tile stapling (post-solve)

After the repair step, attach each isolated tile to the DA whose seed is geographically nearest
(Euclidean distance in row/col space). These tiles are appended to the combined tile list and
included in `persistProposal`.

### 2.9 estimatedDemandMin includes travel overhead

In `persistProposal`, the region's `estimatedDemandMin` now adds `nTiles × INTER_TILE_TRAVEL_MIN`
to the raw demand sum (consistent with how `daTargetLoad` was computed).

### 2.10 Removed constants/methods

- `MAX_LAZY_CUT_ROUNDS = 10` — removed (lazy-cut loop gone)
- Old `computeSeedIndices(long[] scaledDemand, int K)` — replaced by geographic version
- Log format: `"gap={:.1f}%"` → `"gap={}%"` (Java String.format placeholder, not SLF4J)

---

## 3. DemandScoringServiceImpl — M4DataLoader Extraction

### 3.1 Extract M4 queries to `M4DataLoader`

The 4 private query methods (`loadServiceTimeMins`, `loadInterStopTravelMins`, `loadCurrentOrders`,
`loadHistAvgOrders`) are moved out of `DemandScoringServiceImpl` into a new package-private
`@Service` class `M4DataLoader`.

Each method in `M4DataLoader` carries `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
This is the key fix: when `shipment_leg_events` doesn't exist, the inner REQUIRES_NEW transaction
rolls back by itself without corrupting the outer `DemandScoringServiceImpl` transaction.
Previously, the outer transaction was also getting marked rollback-only, causing
`UnexpectedRollbackException` to bubble up.

Replace the `EntityManager entityManager` field/constructor arg in `DemandScoringServiceImpl` with
`M4DataLoader m4`. Call sites become:
```java
Map<UUID, Double> serviceTimeMins = safeCall(() -> m4.loadServiceTimeMins(properties.getBootstrap().getMinPickupsForRealData()), Map.of());
Map<UUID, Double> interStopTravelMins = safeCall(() -> m4.loadInterStopTravelMins(properties.getSolver().getMinInterStopPairsPerWindow()), Map.of());
Map<UUID, Integer> currentOrders = safeCall(() -> m4.loadCurrentOrders(date), Map.of());
Map<UUID, Double> histAvgOrders = safeCall(() -> m4.loadHistAvgOrders(date), Map.of());
```

### 3.2 Snapshot existence check

At the top of `computeDemandSnapshots`, before running any queries, check if all active tiles
already have demand snapshots for the requested date. If yes, return them immediately. This
preserves any manual demand overrides and avoids redundant recomputation on same-day reruns.

```java
Set<UUID> activeTileIds = activeTiles.stream().map(Tile::getId).collect(Collectors.toSet());
Map<UUID, TileDemandSnapshot> existingByTile = snapshotRepository.findBySnapshotDate(date).stream()
        .filter(s -> activeTileIds.contains(s.getTileId()))
        .collect(Collectors.toMap(TileDemandSnapshot::getTileId, s -> s, (a, b) -> b));
if (existingByTile.keySet().containsAll(activeTileIds)) {
    return new ArrayList<>(existingByTile.values());
}
```

Note the `(a, b) -> b` merge function in `toMap` — deduplicates in case of multiple snapshots
per tile (possible from manual demo inserts).

### 3.3 safeCall wrapper

Add a private helper to `DemandScoringServiceImpl` that catches any exception from an M4DataLoader
call and returns the fallback instead:

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

New package-private `@Service` class in `com.oneday.grid.service.impl`.

Wraps the 4 native SQL queries that query M4's `shipment_leg_events` table, each in its own
`REQUIRES_NEW` transaction. Methods accept threshold params instead of reading from properties
internally (easier to test).

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

468-line new `@Service` (`@Qualifier("balancedBfsAssignmentService")`). A third solver variant
between plain BFS and CP-SAT:

**Algorithm:**
1. BFS initial partition — same greedy expansion as `BfsAssignmentServiceImpl` to get K starting
   territories.
2. Local swap refinement — up to `MAX_SWAP_PASSES = 300` passes where boundary tiles are
   considered for reassignment between adjacent DA territories if the swap reduces load imbalance.

Use case: when CP-SAT is overkill (small K or time-sensitive) but plain BFS produces visibly
unbalanced territories. The `GridReplanServiceImpl` `@Qualifier` still points at CP-SAT as the
primary solver; this class is available but not yet wired in as default.

---

## 6. Supporting Changes

### `SolverType.java`
Add `BALANCED_BFS` to the enum:
```java
public enum SolverType { CP_SAT, BFS_FALLBACK, BALANCED_BFS, MANUAL }
```

### `AssignmentProposal.java`
Add Hibernate JSON type hint on `understaffedTileIds` to avoid serialization warnings on JSONB
columns:
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "understaffed_tile_ids", columnDefinition = "jsonb")
private String understaffedTileIds;
```
Imports: `org.hibernate.annotations.JdbcTypeCode`, `org.hibernate.type.SqlTypes`.

### `DaTileAssignmentRepository.java`
Add two new query methods:
```java
// Single tile, multiple statuses (e.g. tile detail panel)
List<DaTileAssignment> findByTileIdAndValidDateAndStatusIn(
        UUID tileId, LocalDate validDate, Collection<AssignmentStatus> statuses);

// City-scoped, multiple statuses (e.g. APPROVED + ACTIVE both count as "live")
List<DaTileAssignment> findByTileIdInAndValidDateAndStatusIn(
        Collection<UUID> tileIds, LocalDate validDate, Collection<AssignmentStatus> statuses);
```

### `GridProperties.java`
Solver time limit: `timeLimitSeconds = 60` → `timeLimitSeconds = 45`.

### `GridReplanServiceImpl.java`
Cosmetic rename: field `cpSatAssignmentService` → `assignmentService`. No behavior change.

---

## What NOT to port

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
| `M4DataLoader` test changes | Demo-specific test setup; keep existing unit tests |
