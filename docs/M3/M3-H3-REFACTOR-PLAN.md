# M3 — H3 Refactor Plan

| Field | Value |
|-------|-------|
| Status | Planning — no code written yet |
| Scope | Replace rectangular 2km×2km tiles with Uber H3 hexagonal cells across the entire `grid` module |
| Decision basis | See `M3-GRID-SHAPE-COMPARISON.md` — after holding algorithm quality constant, H3 was selected for future city-scaling and polyfill convenience |
| Resolution target | H3 resolution 7 (avg 5.16 km², ~1.22 km edge) — closest match to 2 km operating scale |

---

## Flyway Migration Strategy

**New table names. V1–V10 stay on disk untouched.**

Flyway refuses to re-run already-recorded migrations. Touching V1–V10 would corrupt `flyway_schema_history` on every local and CI environment. The clean path:

- V1–V10 remain as-is. Old tables (`grid`, `tile`, `tile_travel_time`, `grid_vertex`, etc.) live on in Postgres — no data loss, no `flyway repair`.
- V11–V17 create new `h3_*` prefixed tables. JPA entities get `@Table(name = "h3_...")`. Old tables become inert reference data queryable for comparison during development.
- `assignment_proposal` and `assignment_proposal_region` are **reused unchanged** — they carry no tile geometry. The new `da_hex_assignment` table has a FK to `assignment_proposal(id)`.
- When H3 is confirmed stable, V18+ can drop the old tables. That cleanup is optional and can wait.

---

## What Changes vs. What Survives

### Rectangle-specific code that must change

| Location | Why it's rectangle-specific |
|----------|----------------------------|
| `Grid` entity | `originLat/originLon/tileDeltaLat/tileDeltaLon` — bounding-box anchors |
| `Tile` entity | `rowIdx/colIdx` — rectangular grid coordinates |
| `GridVertex` entity | `rowIdx/colIdx` — lattice point indices |
| `GridServiceImpl.initializeGrid` | Bounding-box + nested row/col loop |
| `GridServiceImpl.getTileAt` | `(lat − origin) / delta` arithmetic |
| `GridServiceImpl.getTileDetails` | SW/NE corner computation from `origin + row × delta` |
| `OsrmMatrixServiceImpl` | Centroid formula `origin + (row+0.5)×delta`; SW→NE traversal cap OSRM call |
| `CpSatAssignmentServiceImpl` | `tileRows[]`/`tileCols[]` arrays for seed selection and distance penalty |
| V1/V2/V3/V5 migrations | origin/delta columns, `row_idx/col_idx` UNIQUE constraints, lattice vertex table |

### Shape-agnostic code that survives unchanged

- `AssignmentProposal`, `AssignmentProposalRegion` entities — no tile geometry
- `BfsAssignmentServiceImpl` — pure UUID-based adjacency graph BFS
- `ContiguityValidator` — pure graph BFS
- `ProposalServiceImpl` — no geometry
- All Kafka events, producers, and consumers
- `IntradayLoadScoreService`, `IntradayMonitorJob`
- `NightlyReplanJob`, `GridReplanService` — orchestration only
- All enums (`ProposalStatus`, `SolverType`, `AdjacencySource`, `AssignmentStatus`)

---

## Phase A — Dependency + New Migrations

**Goal:** Foundation. No Java logic changes. Everything else compiles against this.

### A.1 — Add h3-java dependency

```xml
<!-- grid/pom.xml -->
<dependency>
    <groupId>com.uber.h3core</groupId>
    <artifactId>h3-java</artifactId>
    <version>4.1.1</version>
</dependency>
```

Auto-loads the correct native binary (ARM64 on M-series Macs, AMD64 on Linux CI). No classifier needed for standard platforms.

### A.2 — Register H3Core as a singleton Spring bean

Create `grid/src/main/java/com/oneday/grid/config/H3Config.java`:

```java
@Configuration
public class H3Config {
    @Bean
    public H3Core h3Core() throws IOException {
        return H3Core.newInstance();
    }
}
```

`H3Core.newInstance()` loads the native library — must be a singleton, not constructed inline.

### A.3 — Flyway migrations V11–V17

| Migration | Table | Key difference from old design |
|-----------|-------|-------------------------------|
| V11 | `h3_grid` | `h3_resolution INT NOT NULL` replaces `origin_lat/lon/delta_lat/lon` |
| V12 | `h3_hex` | `h3_index BIGINT NOT NULL` replaces `row_idx/col_idx`; UNIQUE on `(h3_grid_id, h3_index)` |
| V13 | `h3_hex_travel_time` | Same structure as old, FKs point to `h3_hex` |
| V14 | `h3_pincode_mapping` | `hex_id UUID REFERENCES h3_hex(id)` replaces `tile_id` |
| V15 | `h3_hex_vertex` | `h3_vertex_index BIGINT NOT NULL` replaces `row_idx/col_idx`; H3 vertex indexes are globally unique |
| V16 | `h3_hex_demand_snapshot` | `hex_id UUID REFERENCES h3_hex(id)` replaces `tile_id` |
| V17 | `da_hex_assignment` | `hex_id UUID REFERENCES h3_hex(id)`; `proposal_id UUID REFERENCES assignment_proposal(id)` (reuses existing proposal table) |

**Deliverable:** `mvn clean install -pl grid` passes. No H3 logic yet — just wired dependency and clean migrations.

---

## Phase B — Domain Layer

**Goal:** Rename/update JPA entities to point to new H3 tables. No service logic yet.

| Old entity | New entity | Table | What changes |
|-----------|-----------|-------|-------------|
| `Grid` | `Grid` (keep name) | `h3_grid` | Remove `originLat/Lon/deltaLat/Lon`; add `h3Resolution int` |
| `Tile` | `Hex` | `h3_hex` | Remove `rowIdx/colIdx`; add `h3Index long` |
| `GridVertex` | `HexVertex` | `h3_hex_vertex` | Remove `rowIdx/colIdx`; add `h3VertexIndex long` |
| `TileTravelTime` | `HexTravelTime` | `h3_hex_travel_time` | FK fields renamed `fromHexId`/`toHexId` |
| `PincodeMapping` | `PincodeMapping` (keep) | `h3_pincode_mapping` | FK field renamed `hexId` |
| `TileDemandSnapshot` | `HexDemandSnapshot` | `h3_hex_demand_snapshot` | FK field renamed `hexId` |
| `DaTileAssignment` | `DaHexAssignment` | `da_hex_assignment` | FK field renamed `hexId` |
| `AssignmentProposal` | unchanged | `assignment_proposal` | No change |
| `AssignmentProposalRegion` | unchanged | `assignment_proposal_region` | No change |

**Deliverable:** All entities compile cleanly against new table names.

---

## Phase C — Repository Layer

**Goal:** Update repository interfaces to match new entity fields.

Key query changes:

| Old query | New query |
|-----------|-----------|
| `findByGridIdAndRowIdxAndColIdx(UUID, int, int)` | `findByH3GridIdAndH3Index(UUID gridId, long h3Index)` |
| `findByGridIdAndActiveTrue(UUID)` | `findByH3GridIdAndActiveTrue(UUID)` |
| `findByGridId(UUID)` on vertex repo | `findByH3GridId(UUID)` |
| `deleteByGridId(UUID)` on travel time repo | `deleteByH3GridId(UUID)` |
| `findByTileIdInAndValidDateAndStatus(...)` | `findByHexIdInAndValidDateAndStatus(...)` |

`AssignmentProposalRepository` and `AssignmentProposalRegionRepository` are unchanged.

**Deliverable:** All repositories compile against new entities.

---

## Phase D — GridService (Core Tile Logic)

**Goal:** Replace all rectangle arithmetic with H3 API calls. This is the largest phase.

### D.1 — `initializeGrid` rewrite

```
Old approach:
  1. Compute origin lat/lon from pincode bbox min - padding
  2. Compute tileDeltaLat = 2km / 111.32 km/°
  3. Compute tileDeltaLon = 2km / (111.32 × cos(centerLat))
  4. Nested loop over (row, col) → create every tile in the bounding box
  5. For each pincode centroid → floor((lat - origin) / delta) → activate tile
  6. Buffer: activate N/S/E/W neighbors of each activated tile
  7. Vertex loop: (M+1)×(N+1) lattice corners from origin + row×delta

New approach:
  1. For each pincode centroid → h3.latLngToCell(lat, lon, resolution) → collect into Set<Long>
  2. Buffer: for each pincode hex → h3.gridDisk(hex, 1) → add all to active set
     (gridDisk returns 6 equidistant neighbors, replaces the N/S/E/W offset logic)
  3. Insert one h3_hex row per hex in the active set (h3Index = the long cell index)
  4. Vertex extraction:
       for each active hex → h3.cellToVertexes(hex) → Set<Long> vertexIndexes
       (H3 vertex indexes are globally unique — insertion into a Set deduplicates for free)
       for each unique vertex → h3.vertexToLatLng(vertexIndex) → insert h3_hex_vertex row
  5. No per-city origin/delta stored — h3_grid only stores city_id + h3_resolution
```

### D.2 — `getTileAt` rewrite

```java
// Old
int row = (int) Math.floor((lat - grid.getOriginLat()) / grid.getTileDeltaLat());
int col = (int) Math.floor((lon - grid.getOriginLon()) / grid.getTileDeltaLon());
return hexRepository.findByH3GridIdAndRowIdxAndColIdx(grid.getId(), row, col);

// New
long h3Index = h3Core.latLngToCell(lat, lon, grid.getH3Resolution());
return hexRepository.findByH3GridIdAndH3Index(grid.getId(), h3Index);
```

### D.3 — `getTileDetails` rewrite

Old: computed SW/NE corners as `origin + row × delta`.

New: return centroid from `h3Core.cellToLatLng(hex.getH3Index())`. The response DTO changes from `swLat/swLon/neLat/neLon` to `centerLat/centerLon`. M6 hasn't been built yet so changing the response shape is free.

### D.4 — `getVertices`

No structural change — still loads `HexVertex` rows and returns lat/lon. Column names in response update: remove `rowIdx/colIdx`.

**Deliverable:** `POST /api/grid/admin/init?cityCode=delhi` runs end-to-end and populates `h3_hex` + `h3_hex_vertex` in Postgres.

---

## Phase E — OsrmMatrixService

**Goal:** Replace the two geometry formulas. The OSRM table call logic is unchanged.

### E.1 — Centroid computation

```java
// Old
double centLat = grid.getOriginLat() + (tile.getRowIdx() + 0.5) * grid.getTileDeltaLat();
double centLon = grid.getOriginLon() + (tile.getColIdx() + 0.5) * grid.getTileDeltaLon();

// New
com.uber.h3core.util.LatLng centroid = h3Core.cellToLatLng(hex.getH3Index());
double centLat = centroid.lat;
double centLon = centroid.lng;
```

### E.2 — Traversal cap

Old: OSRM route from SW corner (`origin + row×delta`) to NE corner (`origin + (row+1)×delta`).

New: Use `h3Core.getHexagonEdgeLengthAvg(resolution, LengthUnit.m)` as the cap estimate, or keep the OSRM call with `centroid ± (edgeLength/2)` as endpoints. Either approach is correct; the OSRM call is more accurate for road-network topology.

**Deliverable:** OSRM matrix refresh populates `h3_hex_travel_time` correctly.

---

## Phase F — Assignment Services

**Goal:** Replace row/col coordinate arrays with lat/lon centroid arrays. BFS and contiguity logic is unchanged.

### F.1 — `CpSatAssignmentServiceImpl`

The only rectangle-specific code is in `computeSeedIndices` and the distance penalty inside `trySolve`. Both use `tileRows[]`/`tileCols[]` arrays.

```java
// Old — SeedResult record
private record SeedResult(int[] seeds, int[] tileRows, int[] tileCols) {}

// In computeSeedIndices:
Tile t = tileMap.get(demand.get(i).getTileId());
row[i] = t != null ? t.getRowIdx() : 0;
col[i] = t != null ? t.getColIdx() : 0;

// New — SeedResult record
private record SeedResult(int[] seeds, double[] hexLats, double[] hexLons) {}

// In computeSeedIndices:
Hex hex = hexMap.get(demand.get(i).getHexId());
LatLng c = h3Core.cellToLatLng(hex.getH3Index());
lat[i] = c.lat; lon[i] = c.lng;
```

Distance computation is identical — Euclidean in degree space is fine at city scale (~111 km/°). The furthest-first seed selection algorithm, load-balance model, BFS repair phase, and isolated hex stapling are all unchanged.

The `tileMap` (`Map<UUID, Tile>`) becomes `hexMap` (`Map<UUID, Hex>`).

### F.2 — `BfsAssignmentServiceImpl`

If it uses row/col for seed selection: same lat/lon swap as above. BFS traversal (UUID adjacency graph) is unchanged.

### F.3 — `ContiguityValidator`

Pure graph BFS. **No change.**

**Deliverable:** CP-SAT and BFS proposals compute correctly with H3 hexes.

---

## Phase G — DTO + API Layer

**Goal:** Update response shapes to remove row/col, expose H3 identifiers.

### DTO field changes

| Old field | New field | Notes |
|-----------|-----------|-------|
| `rowIdx`, `colIdx` | `h3Index` (String) | H3 indexes are 64-bit longs; return as hex string e.g. `"87283472fffffff"` |
| `swLat, swLon, neLat, neLon` | `centerLat, centerLon` | From `h3Core.cellToLatLng()` |
| `GridVertexResponse.rowIdx/colIdx` | removed | Vertex is identified by `lat/lon` only from the caller's perspective |
| `TileAtResponse.rowIdx/colIdx` | `h3Index` (String) | Caller needs cell identity, not grid coordinates |

### API URL paths

Keep `/api/grid/...` unchanged. No consumer module (M4/M5) exists yet so this is a free change, but keeping stable URLs reduces future integration risk.

**Deliverable:** `GET /api/grid/delhi/tiles?date=...` returns hex centroids. `GET /api/grid/delhi/tile-at?lat=...&lon=...` returns H3 cell index string.

---

## Phase H — Test Updates

**Goal:** Update unit tests to use `Hex` objects instead of `Tile` with row/col.

### Key changes per test file

| Test | Change |
|------|--------|
| `GridServiceImplTest` | Replace `Tile.builder().rowIdx(r).colIdx(c)` with `Hex.builder().h3Index(h3Core.latLngToCell(lat, lon, 7))` |
| `CpSatAssignmentServiceImplTest` | Replace synthetic row/col grid with lat/lon centroid grid; test logic (N tiles, K DAs, adjacency edges) is structurally identical |
| `BfsAssignmentServiceImplTest` | Same entity swap as CP-SAT test |
| `OsrmMatrixServiceImplTest` | Update centroid extraction assertion |
| `ContiguityValidatorTest` | **No change** — fully adjacency-graph based |
| `ProposalServiceImplTest` | Entity rename only (`DaTileAssignment` → `DaHexAssignment`) |

**Note on test H3 setup:** Avoid real `H3Core` calls in pure unit tests. Pre-compute a set of valid H3 indexes as test constants (e.g., a 3×3 synthetic hex grid over Delhi centroid) and hardcode them. Use `@Bean`-injected `H3Core` only in integration tests.

**Deliverable:** `mvn test -pl grid` passes, 0 failures.

---

## Phase I — Config + Docs

**Goal:** Cleanup and alignment.

1. **`GridProperties`:** Add `h3Resolution: 7` (or per-city map under `grid.cities`). Remove any delta-related config that was only used in bounding-box computation.

2. **`serviceability/*.yaml`:** Remove the `centerLat` field — it was only used to compute `tileDeltaLon` (longitude correction factor). Pincode entries with `lat/lon` are unchanged and feed directly into `h3.latLngToCell()`.

3. **`application.yml`:** Add under `grid:`:
   ```yaml
   grid:
     h3:
       resolution: 7
   ```

4. **Docs to update:** `M3-GRID-DESIGN.md`, `M3-IMPLEMENTATION-PLAN.md`. The `M3-GRID-SHAPE-COMPARISON.md` can be archived — its decision is recorded here.

---

## Phase Summary

| Phase | What | Estimated scope |
|-------|------|----------------|
| A | h3-java dep + H3Config bean + V11–V17 migrations | 1–2 hours |
| B | Domain entities renamed/updated | 1–2 hours |
| C | Repositories updated | 1 hour |
| D | GridService — init + GPS lookup + vertices | ~half day |
| E | OsrmMatrixService — centroid + traversal cap | 1–2 hours |
| F | CP-SAT + BFS seed selection (lat/lon swap) | 2–3 hours |
| G | DTOs + API response shapes | 1–2 hours |
| H | Test updates | ~half day |
| I | Config + docs cleanup | 1 hour |

**Total estimate: 2.5–3 focused days.**

Everything downstream of the adjacency graph — BFS, CP-SAT load balance, contiguity repair, proposals, Kafka, intraday monitoring — is already shape-agnostic and requires no changes. The refactor is almost entirely confined to three questions: how a GPS coordinate maps to a cell, how cell centroids are computed, and how cell vertices are extracted. Everything else is renaming.

---

## Cross-Phase Invariants to Preserve

These design rules from `M3-GRID-DESIGN.md` are unchanged by the H3 refactor:

- **Append-only tables:** `da_hex_assignment`, `assignment_proposal`, `assignment_proposal_region`, `h3_hex_vertex` — never mutate.
- **No live OSRM at replan time:** Nightly job reads `h3_hex_travel_time` from DB. OSRM only called in refresh jobs.
- **Bootstrap mode:** All demand scoring paths handle `isBootstrapped` flag. Solver runs without M4 data.
- **70/30 demand weighting:** Unchanged — lives in `DemandScoringService`, not in geometry.
- **Cron-meeting hard constraint:** Unchanged — lives in M5.
- **No PostGIS:** H3 index arithmetic replaces all geometry. `h3-java` does its own coordinate math natively.
