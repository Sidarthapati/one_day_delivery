# M3 — Grid Shape Comparison: H3 Hexagonal vs Rectangular Tiles

| Field | Value |
|-------|-------|
| Status | Reference document — decision recorded in M3-GRID-DESIGN.md |
| Scope | DA territory grid shape choice for Indian city-scale logistics (2 km granularity) |
| Assumption | Algorithm quality is held constant: both approaches use OSRM road-time adjacency, time-based demand scoring (minutes not orders), and CP-SAT global partition solver. The grid shape is the only variable being evaluated. |

---

## 1. Why This Question Arises

The grid shape question is legitimate because Swiggy, Blinkit, and Uber all use H3 hexagonal grids. The natural question is: should a logistics platform modelled on similar territory assignment principles also use H3?

This document answers that question rigorously, holding algorithm quality constant so the grid shape itself is the only variable. If the demand metric is minutes, the adjacency is road-time via OSRM, and the solver is CP-SAT, does the tile shape still matter?

**Short answer: Yes, but not for algorithm quality reasons. It matters for model size, operational legibility, and M6 integration complexity.**

---

## 2. What Changes When You Hold Algorithm Quality Constant

If you assume identical:
- Demand scoring: `demand_minutes = service_time_per_order + inter_stop_travel_per_order` (not order count)
- Adjacency: OSRM road travel time (not geometric k-ring or 4-connectivity)
- Solver: CP-SAT with lazy-cuts contiguity enforcement

Then the DA territory partition quality — how well-balanced the territories are, how well they respect road barriers — is determined by the algorithm, not the grid shape.

The grid shape then affects five other things:
1. CP-SAT model size (number of nodes = number of tiles/hexes)
2. Van stop vertex computation for M6
3. Operational legibility (can a DA understand their zone?)
4. City initialization complexity
5. Implementation effort and library dependencies

These are the dimensions this document evaluates.

---

## 3. The Geometry Briefly

### 3.1 Rectangular tiles (current M3)

Fixed 2 km × 2 km tiles anchored to a city-specific bounding box. A city grid has M rows × N columns. Each tile identified by `(row_idx, col_idx)`.

```
GPS → tile:  row = floor((lat - origin_lat) / tile_delta_lat)
             col = floor((lon - origin_lon) / tile_delta_lon)
(Two subtractions, two integer divisions. No library.)

Adjacency:   tiles (r1,c1) and (r2,c2) share an edge iff |r1-r2| + |c1-c2| == 1
(One arithmetic expression. No library. 4 neighbors maximum.)

Van vertices: (M+1) × (N+1) lattice corners. Computed from origin + (row,col) × delta.
```

### 3.2 H3 hexagonal grid

Uber's H3 is a hierarchical global grid of hexagonal cells. Resolution determines cell size:

| H3 Resolution | Avg cell area | Avg edge length |
|---|---|---|
| 7 | 5.16 km² | 1.22 km |
| 8 | 0.74 km² | 0.46 km |
| 9 | 0.11 km² | 0.17 km |

A 2 km × 2 km tile = 4 km². The closest H3 resolution is 7 (5.16 km²) — but that's 30% larger than a 2km tile. Resolution 8 (0.74 km²) is too small — you'd need ~5-6 resolution-8 hexes to cover one 2km tile. **There is no H3 resolution that cleanly maps to 2 km.** You must either accept coarser or finer granularity.

If you choose resolution 8 to get finer grain coverage:
- ~5.4 hexes per equivalent 2km tile area
- A city with ~150 active tiles → ~810 active hexes in the CP-SAT model
- Model is 5.4× larger for the same real-world outcome

```
GPS → hex:   h3.latLngToCell(lat, lon, resolution)
(Library call. Globally defined — no city origin needed.)

Adjacency:   h3.gridDisk(hex, 1) returns 6 neighbors
(Library call. 6 equidistant neighbors. Still needs OSRM to filter by road time.)

Van vertices: h3.cellToVertexes(hex) + deduplication across hexes
(Library call + postprocessing. No natural lattice structure.)
```

---

## 4. Pros and Cons — Head to Head

### 4.1 Spatial Geometry

**H3 advantage: 6 equidistant neighbors**

In a hexagonal grid every neighbor centroid is equidistant from the cell centroid. In a rectangular grid, edge-adjacent neighbors are distance d, but diagonal neighbors are distance d√2 ≈ 1.41d. This means rectangular adjacency is geometrically non-uniform.

However: with OSRM road-time adjacency (which both approaches use when holding algorithm quality constant), this geometric argument becomes irrelevant. The adjacency threshold is a travel-time cutoff (e.g. 600 seconds), not a geometric distance. OSRM measures actual road travel, which has no preference for cardinal vs diagonal directions. The 6-vs-4 neighbor uniformity advantage of H3 does not survive the OSRM filter.

**Verdict at 2 km with OSRM: Neutral. The geometric advantage of hexagons is nullified by road-time adjacency.**

---

**H3 advantage: variable resolution**

H3 cells can be coarser in sparse suburban areas and finer in dense urban cores. Resolution 7 in sparse outskirts, resolution 8 in Connaught Place. This adapts tile granularity to where demand actually is.

Rectangular tiles are fixed at 2 km everywhere. A sparse outer tile and a dense inner tile get the same geometry.

However: the variable resolution benefit is meaningful only when you want sub-500m granularity in dense areas. At 2 km, the same density variation is handled by the multi-DA tile pre-processing (Component C in M3-GRID-DESIGN.md §5.4) — a dense tile gets multiple DAs assigned to it rather than being split into smaller tiles. The result is equivalent at operating scale.

**Verdict at 2 km: H3 marginal advantage. Not yet needed at this granularity; worth reconsidering below 500m.**

---

**Rectangle advantage: alignment with Indian city street patterns**

Commercial cores of Indian metros — Delhi's Connaught Place, Bangalore's central business district, Mumbai's Nariman Point — have largely block-based street patterns. A rectangular tile boundary can be oriented to approximate a major road or arterial, making the zone boundary explainable to a DA without a map.

H3 boundaries cross streets at 30° or 60° angles regardless of the road layout.

**Verdict: Rectangle advantage. Real for operational communication.**

---

### 4.2 Model Size (CP-SAT)

This is the most important practical difference.

| Metric | Rectangle (M3) | H3 Resolution 8 |
|---|---|---|
| Tiles covering a typical Indian city | ~150 active tiles | ~810 equivalent hexes |
| CP-SAT decision variables | ~150 | ~810 |
| Lazy-cuts BFS nodes per round | ~150 | ~810 |
| Approximate solve time (our scale) | < 10 seconds | 20–60 seconds (estimated) |
| Approximation relative to optimal | Same (same solver) | Same (same solver) |

The partition quality is identical — same solver, same demand data. But H3 at resolution 8 builds a 5× larger model, takes 3–5× longer to solve, and consumes more memory per replan for no improvement in the output assignment.

If you coarsen to resolution 7 (5.16 km²), the model size drops to ~30 hexes, but your tile area is 30% larger than intended and you lose granularity in dense areas.

**Verdict: Strong rectangle advantage. H3 at matching resolution creates unnecessary model complexity.**

---

### 4.3 Van Stop Vertices (M6 Integration)

This is the most concrete, least discussed difference.

**Rectangle:** M3 maintains a `grid_vertex` table with `(M+1) × (N+1)` lattice corner points. Each vertex has a stable `(row_idx, col_idx)` identifier. M6 reads this table directly. Picking a DA territory's meeting point = find the vertex at the corner shared by the most tiles in the territory. One arithmetic lookup.

```sql
-- M6: get all meeting vertices for today's territories
SELECT gv.id, gv.lat, gv.lon, dta.da_id
FROM grid_vertex gv
JOIN da_tile_assignment dta ON ...
-- One vertex per territory, directly from M3's pre-computed table
```

**H3:** Hex corners are not a regular lattice. Each resolution-8 hex has 6 corners. Multiple hexes share corners (each corner is shared by exactly 3 hexes). To build van stop candidates:

```
For each territory (set of hexes):
  1. Call h3.cellToVertexes(hex) for each hex in territory.
  2. Collect all vertex IDs (H3 vertex indexes, not coordinate-based).
  3. Find boundary vertices (corners where the territory meets another territory).
  4. Pick one as the meeting point (e.g. most central boundary vertex).
  5. Call h3.vertexToLatLng(vertex) to get coordinates.
  6. Validate that this point is on a road via OSRM snap.
  7. Handle the case where the vertex lands in a building or river.
```

Steps 1–7 replace one JOIN in the rectangular case. This is not impossible — the h3-java library has all the functions needed — but it is a non-trivial M6 implementation task. More critically, the resulting meeting points are not stable: if the territory shape changes slightly (one hex moves), the boundary vertices change, and the van stop point may shift.

**Verdict: Strong rectangle advantage. M6 vertex integration is trivial for rectangular tiles and non-trivial for H3.**

---

### 4.4 City Initialization

**Rectangle:** Each city requires one-time setup — compute `origin_lat`, `origin_lon`, `tile_delta_lat`, `tile_delta_lon`, then generate the tile grid from pincode centroids.

**H3:** No per-city initialization needed for the geometry layer. Any GPS coordinate in the world maps to an H3 cell via `h3.latLngToCell()`. No bounding box. No origin computation. To activate cells for a city: call `h3.latLngToCell(pincode.lat, pincode.lon, resolution)` for each serviceable pincode and mark those cells active. That's it.

**Verdict: H3 advantage. Significantly simpler city onboarding for the geometry layer.** This is a real time-saver if you're onboarding many cities quickly.

---

### 4.5 Operational Legibility

**Rectangle:** A station manager briefing a new DA says: "Your zone this week runs from Bandra Station to Linking Road, between Hill Road and Waterfield Road — that's the R4-C2 tile." The DA can navigate this without opening an app.

**H3:** "Your zone is hex 8928308280bffff." The DA has no mental model of this without looking at a map application. The hex boundary cuts across blocks at 30° angles, which does not match landmarks a DA would use for orientation.

**Verdict: Strong rectangle advantage.** This compounds daily across every DA briefing, every station manager override, every customer support call about zone boundaries.

---

### 4.6 GPS Coordinate Lookup Performance

**Rectangle:** O(1) arithmetic. Two subtractions, two integer divisions. No library call. Cacheable in 64 bytes (origin + deltas). Used in the hot path: M4 calls M3 at every booking (`GET /grid/serviceability`), and M5 calls M3 at every GPS ping (`GET /grid/tile-at`).

**H3:** Library call to `h3.latLngToCell()`. Internally uses a lookup table + bit manipulation. In practice: single-digit microseconds. Not meaningfully slower than integer arithmetic at 1,000 calls/second. At M5's GPS polling rate, this is not a practical bottleneck.

**Verdict: Neutral in practice.** The theoretical O(1) advantage of rectangles does not translate to a measurable performance difference at this system's scale.

---

### 4.7 Summary Table

| Dimension | Rectangle | H3 | Winner |
|---|---|---|---|
| Spatial uniformity (geometric neighbors) | 4 cardinal neighbors, √2 diagonal | 6 equidistant neighbors | H3 (marginal at 2 km) |
| Spatial uniformity with OSRM adjacency | OSRM overrides geometry | OSRM overrides geometry | Neutral |
| Variable resolution per density | Fixed 2 km everywhere | Any resolution per hex | H3 (not needed at 2 km) |
| CP-SAT model size at 2 km | ~150 nodes | ~810 nodes (res-8) | Rectangle |
| CP-SAT output quality | Identical | Identical | Neutral |
| Van stop vertices (M6) | Pre-computed lattice table, trivial | Hex corners require extraction + dedup + validation | Rectangle |
| City initialization | Per-city bounding box setup | Zero per-city setup | H3 |
| Operational legibility | Row/col → street names | Hex index → map required | Rectangle |
| GPS lookup (hot path) | O(1) arithmetic | Library call (~μs) | Neutral |
| Alignment with Indian block patterns | Natural alignment | 30° angle mismatch | Rectangle |
| External library dependency | None for geometry | h3-java (JNI-based) | Rectangle |
| Multi-resolution display (future) | Manual aggregation | h3.compact() natively | H3 |
| Polyfill from GeoJSON boundary | Manual pincode mapping | h3.polyfillFromGeoJson() | H3 |

---

## 5. Implementation Complexity

This section is written for a developer choosing between the two approaches, not just a designer.

### 5.1 H3 Functionality That Is Genuinely Useful

The h3-java library (maintained by Uber at `com.uber.h3core:h3-java`) provides several functions that would save real implementation time:

**`h3.polyfillFromGeoJson(geojson, resolution)`**
Given a GeoJSON polygon of a city's serviceable area, returns all H3 cells within it. This replaces M3's current pincode-centroid mapping entirely. Instead of loading pincodes and computing which tile each centroid falls in, you load a city shapefile and polyfill it. One call, zero loops.

**`h3.gridDisk(hex, k)` (formerly k_ring)**
Returns all hexes within k grid steps. Used for neighbor discovery in CP-SAT and BFS. No adjacency arithmetic needed. Note: still requires OSRM filtering for road-time adjacency — this call only gives geometric neighbors.

**`h3.compact(hexSet)` / `h3.uncompact(hexSet, resolution)`**
Aggregates fine hexes into coarser parent hexes where all children are present. Useful for displaying territories to station managers (show coarse shape) vs running the solver (fine resolution). Not available for rectangular tiles without custom code.

**`h3.gridDistance(hex1, hex2)`**
Returns the grid distance (minimum steps) between two hexes. Useful for quick spatial checks without OSRM.

**`h3.areNeighborCells(hex1, hex2)`**
O(1) check if two hexes are immediate neighbors.

**`h3.cellToLatLng(hex)`**
Returns the centroid of a hex. Simple and exact.

**These functions are real time-savers**, particularly `polyfillFromGeoJson` for city setup and `compact/uncompact` for display. They represent battle-tested, globally-correct implementations of operations that would take days to implement correctly from scratch for rectangles.

### 5.2 H3 Implementation Challenges

**Native binary dependency (JNI)**

h3-java bundles native binaries for the actual H3 cell computation. Adding it to a Spring Boot fat JAR requires verifying the native binary is correctly unpacked at runtime. In Docker on Linux AMD64 (your deployment target), it works out of the box. On macOS ARM (M-series chips, your dev machines), you need the ARM64 variant.

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.uber.h3core</groupId>
    <artifactId>h3-java</artifactId>
    <version>4.1.1</version>
</dependency>
```

The library auto-detects the platform and unpacks the correct binary. In practice, this Just Works for standard platforms. It's a one-line dependency, not a complex integration.

**H3Core instantiation as a Spring bean**

`H3Core.newInstance()` is not free — it loads the native library. It must be a singleton:

```java
@Bean
public H3Core h3Core() throws IOException {
    return H3Core.newInstance();
}
```

Inject into any service that needs it. No further complexity.

**Hex ID storage in the database**

H3 cell indexes are 64-bit integers (longs). You can store them as `BIGINT` in PostgreSQL. The current M3 schema uses UUIDs for tile IDs. You have two options:

- **Option A**: Keep UUID as primary key, add `h3_index BIGINT NOT NULL` column. Maintains compatibility with all FK references.
- **Option B**: Use `h3_index` as the primary key directly. Simpler but changes the ID type everywhere.

Option A adds one column to the tile table. Everything else (FKs, APIs) stays the same.

**Van vertex table — the hardest part**

This is where H3 creates genuine implementation complexity. The `grid_vertex` table in M3 is trivially computed from `(row, col, origin, delta)`. For H3, you must:

1. For each active hex, call `h3.cellToVertexes(hex)` — returns up to 6 vertex indexes.
2. Collect all vertex indexes across the city.
3. Deduplicate (each corner is shared by 3 hexes; vertex indexes are globally unique in H3, so dedup is just a `Set` insertion).
4. Call `h3.vertexToLatLng(vertexIndex)` for each unique vertex to get coordinates.
5. Store in a `hex_vertex` table.

This is ~30 lines of Java. Not hard, but it's code you write vs code that doesn't exist in the rectangular case (vertices are pure arithmetic there).

The harder problem: **picking one vertex per DA territory as the van meeting point**. In the rectangular case this is the vertex at the corner of the most tiles in the territory — one SQL query. In the H3 case you need to find boundary vertices (vertices on the outer perimeter of the territory polygon), then pick one. The h3-java library does not have a direct `territoryBoundaryVertexes()` function — you'd implement this as: find all vertices on hexes that have at least one neighbor not in the territory. About 20 lines of Java, correct but non-trivial.

### 5.3 Rectangular Grid Implementation — Where It Is Harder

**Polyfill (city initialization)**

Mapping serviceable pincodes to tiles is done one pincode at a time (centroid → tile via integer division). If you later want to import a city's service area from a shapefile or GeoJSON boundary, you have to implement the polygon-fill algorithm yourself. H3 gives this for free.

In practice, pincode-centroid mapping works and is already implemented. It only becomes a limitation if you want GeoJSON-driven city initialization (useful at scale, not needed for 5-city launch).

**Multi-resolution display**

If the station manager UI wants to show a zoomed-out view of the city with merged territories, you aggregate tiles manually (group by sub-region). H3's `compact()` handles this naturally and produces smaller hex sets for display. Not a core ops requirement, but a real UX convenience.

**Sparse area handling**

A sparse suburb with 2 orders/day gets the same 4 km² tile as a dense commercial core. You handle the imbalance entirely in the algorithm (demand scoring + CP-SAT), not in the geometry. With H3, you could use resolution 7 (5.16 km²) in sparse areas and resolution 8 (0.74 km²) in dense ones — the geometry itself adapts. This requires mixed-resolution logic in the solver, which is non-trivial to implement correctly, but the underlying H3 API supports it.

### 5.4 Implementation Summary

| Task | Rectangle | H3 | Easier |
|---|---|---|---|
| Dependency setup | No geometry library needed | h3-java, one Maven dependency, auto-native-load | Rectangle |
| GPS → cell mapping | 2 arithmetic ops | `h3.latLngToCell()` | Tie (both trivial) |
| Neighbor discovery | `|r1-r2|+|c1-c2|==1` | `h3.gridDisk(hex,1)` | H3 (no custom math) |
| City initialization from pincodes | Bounding box + integer division per pincode | `h3.latLngToCell()` per pincode, no bounding box | H3 |
| City initialization from GeoJSON | Custom polygon fill required | `h3.polyfillFromGeoJson()` | H3 (significant) |
| Van vertex table | Formula: `origin + (row,col) × delta` | Hex vertex extraction + dedup + coord lookup | Rectangle |
| Van meeting point per territory | SQL: max-corner-sharing vertex | Boundary vertex extraction: ~20 lines Java | Rectangle |
| DB storage | UUID tile table (existing) | Add `h3_index BIGINT` column or change PK type | Rectangle |
| Multi-resolution display | Manual tile aggregation | `h3.compact()` / `h3.uncompact()` | H3 |
| CP-SAT model size | ~150 nodes (2 km tiles) | ~810 nodes (res-8), or ~30 nodes (res-7, too coarse) | Rectangle |
| Debugging / logging | `tile(r=5,c=3)` → human-readable | `hex 8928308280bffff` → requires map lookup | Rectangle |

---

## 6. Head-to-Head: The Two Scenarios Where H3 Is Clearly Better

**Scenario A: Sub-500m granularity is required.**

If you ever need tiles finer than 500m (e.g. hyperlocal same-hour delivery in a dense core), rectangular tiles at that scale have meaningful distortion (tile shape is visibly non-square on the ground; diagonal neighbors are 1.41× farther). H3 at resolution 9 (174 m edge) or 10 (65 m edge) handles this correctly. The CP-SAT model would also be larger, but the spatial correctness justifies it.

At 2 km, this scenario does not apply.

**Scenario B: Rapid city expansion with GeoJSON boundaries available.**

If you're onboarding 50 cities in 3 months and each city has a GeoJSON shapefile of its service area, `h3.polyfillFromGeoJson()` cuts initialization from a multi-step pincode-centroid mapping job to a single function call. The time savings are real at that scale.

At 5-city launch with pincode lists already assembled, this scenario does not apply in v1.

---

## 7. Verdict

**At 2 km granularity for a 5-city Indian city launch: rectangular tiles are the correct choice.**

The reasons, in order of weight:

1. **Model size.** H3 at resolution 8 (the closest match to 2 km) produces a CP-SAT model 5× larger than rectangular tiles for identical output quality. Resolution 7 is too coarse. There is no H3 resolution that matches 2 km without this penalty.

2. **M6 van vertex integration.** The `grid_vertex` table is a first-class M3 output consumed by M6. Rectangular tiles produce it for free as a regular lattice. H3 requires hex vertex extraction, deduplication, and boundary-vertex selection — non-trivial implementation work for a module that isn't yet built.

3. **CP-SAT output quality is identical.** With OSRM adjacency, demand in minutes, and CP-SAT as the solver, the algorithm quality is the same regardless of tile shape. The grid shape is not a quality driver at this scale.

4. **Operational legibility.** DA briefings, station manager overrides, and support calls all benefit from zones that can be described in street names. This compounds across every operating day.

5. **No reinvention required.** The rectangle geometry is already implemented, tested (108 unit tests passing), and integrated with the DB schema, OSRM matrix, and proposal workflow. The h3-java library's conveniences (`polyfill`, `compact`) are genuine, but they solve problems you don't currently have.

**When to reconsider H3:**

- If tile size drops below 500m (hyperlocal delivery)
- If city expansion accelerates to 20+ cities and GeoJSON-driven initialization becomes operationally important
- If variable density zones become a hard requirement (dense commercial vs sparse suburban at different resolutions in the same city)

None of these apply in v1.

**On your father's VRPTW suggestion:** The van routing formulation (OR-Tools VRPTW, time budget from hub to all DA meeting points and back, stop time per node) is correct and directly applicable to M6. The only substitution needed: use grid vertices as meeting point nodes instead of hex centroids, and use your already-running OSRM instance for the travel-time matrix instead of Google Maps API. The OR-Tools model structure is identical. This should be the starting design for M6 when you build it.
