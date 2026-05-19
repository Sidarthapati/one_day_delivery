# M3 Phase 5 — Test Failure Fix Plan

**Branch:** `f-m3-design`
**Date written:** 2026-05-18
**Test command:** `mvn test -pl common,grid -am`
**Current state:** 63 tests, 3 Failures, 20 Errors. Target: all green.

---

## Overall Failure Summary

| Group | Test Class | Count | Error Type |
|-------|-----------|-------|-----------|
| 1 | `GridServiceImplTest` | 5 errors | `UnnecessaryStubbingException` |
| 2 | `ProposalServiceImplTest` | 12 errors | `UnnecessaryStubbingException` |
| 3 | `CpSatAssignmentServiceImplTest` | 3 errors | `UnsatisfiedLinkError` |
| 4 | `DemandScoringServiceImplTest` | 3 failures | Assertion failure (wrong values) |

Tests already passing (do not touch): `OrToolsSmokeTest`, `BfsAssignmentServiceImplTest`, `ContiguityValidatorTest`, `IntradayLoadScoreServiceImplTest`, and 3 delegation tests in `CpSatAssignmentServiceImplTest`.

---

## Fix 1 — `GridServiceImplTest` (5 errors)

### What the tests do

`GridServiceImpl` has three responsibilities tested here:

- **`getGrid(cityId)`** — looks up the in-memory `gridCache` (a `ConcurrentHashMap` keyed by `cityId`). Cache is populated in `loadGridCache()` by calling `gridRepository.findAll()` and iterating `g.getCityId()`.
- **`checkServiceability(cityId, pincode)`** — queries `PincodeMappingRepository` and returns a `ServiceabilityResponse` with `serviceable` + `tileId`.
- **`getTileAt(cityId, lat, lon)`** — reads `originLat/Lon` and `tileDeltaLat/Lon` from the cached Grid object; computes `row = floor((lat - originLat) / deltaLat)`, `col = floor((lon - originLon) / deltaLon)`; calls `tileRepository.findByGridIdAndRowIdxAndColIdx`.

### Why they fail

Mockito's `@ExtendWith(MockitoExtension.class)` uses `STRICT_STUBS` mode. It considers a stub "unnecessary" if the stubbed method is **not called during the test method body** — stubs consumed only inside `@BeforeEach` don't count.

`setUp()` contains:
```java
when(grid.getCityId()).thenReturn(cityId);   // line 49 — FLAGGED
```

`grid.getCityId()` is called only inside `loadGridCache()` (which runs in setUp), never in any test method body. For the 5 failing tests (`getGrid_*` and `checkServiceability_*`), the test body never touches the Grid mock's `getCityId()`. Mockito flags it.

The `getTileAt_*` tests pass because they call `stubGridCoords()` which stubs other Grid methods, making the Grid mock "active" in their body — but this doesn't actually fix the getCityId issue; they happen to pass for a different reason (Mockito sees other Grid interactions).

### Fix

In `GridServiceImplTest.setUp()`, change line 49 from:
```java
when(grid.getCityId()).thenReturn(cityId);
```
to:
```java
lenient().when(grid.getCityId()).thenReturn(cityId);
```

`lenient()` opts that specific stub out of the unnecessary-stubbing check while keeping everything else strict. No test logic changes.

**File to edit:** `grid/src/test/java/com/oneday/grid/service/impl/GridServiceImplTest.java`
**Import to add:** `import static org.mockito.Mockito.lenient;`

---

## Fix 2 — `ProposalServiceImplTest` (12 errors)

### What the tests do

`ProposalServiceImpl` handles the full proposal lifecycle:

- **`approve(proposalId, reviewerId)`** — sets proposal status APPROVED, activates its `DaTileAssignment` rows, supersedes any previously APPROVED proposal for the same city+date.
- **`reject(proposalId, reviewerId, notes)`** — sets status REJECTED; guard: must be in PROPOSED.
- **`editRegionInProposal` (Scenario A)** — pre-approval edit: loads adjacency graph from `TileTravelTime` (needs `gridRepository.findByCityId` → `grid.getId()`), validates contiguity, supersedes old DA assignments, inserts new ones.
- **`requestIntradayReassignment` (Scenario B)** — creates an INTRADAY_OVERRIDE proposal: validates tile ownership, checks both DA territories remain contiguous post-move, writes new proposed assignments.
- **`requestTileShare`** — creates an INTRADAY_SHARE proposal; validates tile has at least one ACTIVE assignment.
- **`approveTileShare`** — activates the share proposal's assignments; guard: must be INTRADAY_SHARE type.

### Why they fail (Part A — 11 tests)

`setUp()` stubs:
```java
when(grid.getId()).thenReturn(gridId);   // line 65 — FLAGGED
```

`grid.getId()` is only needed by `loadAdjacencyGraph()`, which is only called inside `editRegionInProposal` and `requestIntradayReassignment`. For all other tests (`approve_*`, `reject_*`, `requestTileShare_*`, `approveTileShare_*`), `grid.getId()` is never called in the test body → 11 tests flagged.

### Why they fail (Part B — 1 additional test)

`requestIntradayReassignment_tileNotBelongingToFromDa_throwsIllegalState` has **two additional unnecessary stubs inside the test body**:

```java
when(gridRepository.findByCityId(cityId)).thenReturn(Optional.of(grid()));        // line 336
when(travelTimeRepository.findByGridIdAndTravelTimeSecondsLessThanEqual(
        eq(gridId), anyInt())).thenReturn(List.of());                              // line 337
```

These are dead stubs. The service throws `IllegalStateException("Tile X is not ACTIVE under DA Y")` **before** it ever calls `loadAdjacencyGraph()` — so `gridRepository` and `travelTimeRepository` are never reached.

### Fix

**Part A:** In `setUp()`, change line 65 from:
```java
when(grid.getId()).thenReturn(gridId);
```
to:
```java
lenient().when(grid.getId()).thenReturn(gridId);
```

**Part B:** In `requestIntradayReassignment_tileNotBelongingToFromDa_throwsIllegalState`, delete lines 336–337 (the two dead stubs).

**File to edit:** `grid/src/test/java/com/oneday/grid/service/impl/ProposalServiceImplTest.java`
**Import to add:** `import static org.mockito.Mockito.lenient;`

---

## Fix 3 — `CpSatAssignmentServiceImplTest` (3 errors)

### What the tests do

`CpSatAssignmentServiceImpl` runs the CP-SAT optimizer:

- **3 delegation tests (already passing):** `emptyDemand_delegatesToBfs`, `emptyDaIds_delegatesToBfs`, `moreDasThanTiles_delegatesToBfs` — these hit the early-return paths that delegate to `BfsAssignmentServiceImpl` without touching OR-Tools at all.
- **3 real solver tests (failing):**
  - `twoTilesTwoDas_equalDemand_cpSatSolvesAndPersists` — 2 tiles with equal demand (400 min each), 2 DAs, no adjacency. Expects CP-SAT to assign one tile per DA, both PROPOSED, 100% coverage.
  - `twoTilesTwoDas_connectedGraph_convergesInOneLazyCutRound` — same setup but with symmetric adjacency (`t0↔t1`). Each DA gets 1 tile (trivially connected). Tests the lazy-cut loop exits in one round.
  - `solvedProposal_optimalityGapIsNonNegative` — verifies `optimalityGapPct ≥ 0.0`.

### Why they fail

```
UnsatisfiedLinkError: 'long com.google.ortools.util.mainJNI.new_Domain__SWIG_2(long, long)'
```

OR-Tools JNI has two layers. The high-level `CpModel` constructor works (used by `OrToolsSmokeTest`) because it uses the Java wrapper. But `model.newIntVar(lb, ub, name)` internally constructs a `Domain(long, long)` via the native `mainJNI`, which requires `Loader.loadNativeLibraries()` to have been called first to load the full native library set. Neither the service nor the test calls this.

### Fix

Add a static initializer to `CpSatAssignmentServiceImpl`:

```java
static {
    com.google.ortools.Loader.loadNativeLibraries();
}
```

This is the correct layer to fix it — the service is responsible for its own native dependency. The test shouldn't need to know about it.

**File to edit:** `grid/src/main/java/com/oneday/grid/service/impl/CpSatAssignmentServiceImpl.java`
**Add after class declaration, before the logger field.**

---

## Fix 4 — `DemandScoringServiceImplTest` (3 assertion failures)

### What the tests do

`DemandScoringServiceImpl.computeAndPersistDemand(cityId, date)` runs 4 sequential native SQL queries against M4's `shipment_leg_events` table and computes a `TileDemandSnapshot` per active tile.

The demand formula:
```
demandOrders  = 0.70 × histAvgOrders + 0.30 × currentOrders
orderEngaged  = serviceTimeMin + interStopTravelMin
demandMinutes = demandOrders × orderEngaged
```

If M4 data is absent, it falls back to bootstrap defaults (`serviceTimeMin=12`, `interStopTravelMin=5`). The 4 SQL queries are:
1. **Q1 service_time** — avg(pickup_completed_at − arrived_at_pickup) per tile (7-day window, min 20 pickups)
2. **Q2 inter_stop_travel** — avg(arrived_at_pickup[n] − pickup_completed_at[n-1]) per tile, winsorised at `traversal_cap_sec` (7-day window, min 5 pairs)
3. **Q3 current_orders** — COUNT(*) per tile for today's shift_date
4. **Q4 hist_avg** — 7-day rolling average of daily order count per tile

### Failing tests

| Test | What it checks | Failure |
|------|---------------|---------|
| `realData_formulaIs70HistPlus30Current` | All 4 queries return real data. Expects `demandScoreOrders=8.5` (0.7×10+0.3×5). | Gets `0.0` |
| `partialBootstrap_missingServiceTime_usesDefaultForThatComponent` | Q1 empty, Q2–Q4 have data. Expects `interStopTravelMin=3.0` (real). | Gets `5.0` (bootstrap default) |
| `onlyCurrentOrdersPresent_histAvgIsZero_demandUses30Pct` | Q4 empty (histAvg=0), Q1–Q3 have data. Expects `demandScoreOrders=3.0` (0.3×10). | Gets `0.0` |

### Why they fail

The `stubQueries` helper creates **one shared mock `q`** for all 4 native query calls and chains all returns on `q.getResultList()`. The intent is correct, but there is a subtle interaction between:
- Mockito's strict stubbing mode (active via `@ExtendWith(MockitoExtension.class)`)
- An inline mock (`mock(Query.class)`) used inside the test but not as a `@Mock` field
- The `any()` argument matcher on `setParameter`

When the shared `q`'s `setParameter` stub doesn't precisely match (or when Mockito resolves the chain differently in strict mode), the service's `try-catch (Exception e) { return Map.of(); }` silently catches the failure and returns an empty map. Since the failing tests all depend on Q2, Q3, or Q4 returning non-empty data, their maps come back empty and the formula produces 0.

The two **passing** DemandScoring tests confirm this: `bootstrapMode_allQueriesEmpty_usesDefaultServiceAndInterStopTime` stubs all 4 as empty (so empty maps are the correct result), and `emptyActiveTiles_returnsEmptySnapshots` never calls the entity manager at all.

### Fix

Replace the shared-mock `stubQueries` approach with **per-query argument-based routing** using `thenAnswer`. Match each invocation by inspecting the SQL string passed to `createNativeQuery`:

```java
private void stubQueries(
        List<Object[]> svcRows,
        List<Object[]> interRows,
        List<Object[]> curRows,
        List<Object[]> histRows) {

    when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
        String sql = inv.getArgument(0, String.class);
        Query q = mock(Query.class, withSettings().defaultAnswer(RETURNS_SELF));
        List<Object[]> result = sql.contains("arrived_at_pickup - arrived_at_pickup") ? svcRows
                : sql.contains("arrived_at_pickup")                                   ? interRows
                : sql.contains("shift_date = :date")                                  ? curRows
                                                                                      : histRows;
        when(q.getResultList()).thenReturn(result);
        return q;
    });
}
```

Key changes:
- Each call to `createNativeQuery` gets its **own fresh mock** — no shared state.
- `withSettings().defaultAnswer(RETURNS_SELF)` makes `q.setParameter(...)` return `q` automatically without explicit stubbing — removing the `any()` matcher issue entirely.
- Routing is by a distinctive SQL substring unique to each query.
- The method signature changes from `List<?>...` to explicit `List<Object[]>` parameters (cleaner and type-safe).

All 4 call sites in the tests update to the new signature.

**File to edit:** `grid/src/test/java/com/oneday/grid/service/impl/DemandScoringServiceImplTest.java`
**Imports to add:** `import static org.mockito.Mockito.withSettings;` and `import static org.mockito.Answers.RETURNS_SELF;`

---

## Implementation Order

Fix in this order — each is independently verifiable:

1. **Fix 1** (`GridServiceImplTest`) — add `lenient()` to one line. Run: `mvn test -pl grid -Dtest=GridServiceImplTest`
2. **Fix 2** (`ProposalServiceImplTest`) — add `lenient()` + delete 2 stubs. Run: `mvn test -pl grid -Dtest=ProposalServiceImplTest`
3. **Fix 3** (`CpSatAssignmentServiceImplTest`) — add static initializer to service. Run: `mvn test -pl grid -Dtest=CpSatAssignmentServiceImplTest`
4. **Fix 4** (`DemandScoringServiceImplTest`) — rewrite `stubQueries`. Run: `mvn test -pl grid -Dtest=DemandScoringServiceImplTest`

Final verification: `mvn test -pl common,grid -am` → should show 63 tests, 0 failures, 0 errors.

After all tests pass, Phase 5 is complete and Phase 6 (Batch Jobs) is unblocked.
