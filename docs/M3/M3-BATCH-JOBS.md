# M3 Batch Jobs — Detailed Reference

This document explains every file in `com.oneday.grid.batch` and the two event stubs in `com.oneday.grid.events`.

---

## Table of Contents

1. [The OSRM Data vs. Software Distinction](#1-the-osrm-data-vs-software-distinction)
2. [GridInitializationJob](#2-gridinitialization-job)
3. [OsrmMatrixRefreshJob](#3-osrmmatrixrefreshjob)
4. [NightlyReplanJob](#4-nightlyreplan-job)
5. [IntradayMonitorJob](#5-intradaymonitorjob)
6. [DaRosterPort and NoOpDaRosterPort](#6-darosterport-and-noopdaRosterport)
7. [Event Stubs: NoDaAlertProducer and TileOverloadAlertProducer](#7-event-stubs)
8. [How the Jobs Chain Together](#8-how-the-jobs-chain-together)
9. [What Is Not Yet Implemented](#9-what-is-not-yet-implemented)

---

## 1. The OSRM Data vs. Software Distinction

This is the single most important concept for operating `OsrmMatrixRefreshJob`.

**OSRM (Open Source Routing Machine)** is a self-hosted routing engine. It loads a road network snapshot from an OpenStreetMap (OSM) data file and answers queries like "how many seconds does it take to drive from point A to point B?".

There are two things that could change:

| Thing | How it changes | Effect |
|-------|---------------|--------|
| **OSRM software** (the binary / Docker image) | New releases from the OSRM project | Routing algorithm improvements, bug fixes |
| **OSM road data** (the `.osm.pbf` file loaded into OSRM) | New roads built, speed limits changed, flyovers opened, roads closed | Different travel times returned |

**The monthly `OsrmMatrixRefreshJob` only matters when the OSM road data has changed.** If you run the job against an OSRM server that still has old road data, it will return the same travel time matrix as before — the DB update is a no-op.

### The correct ops workflow

```
1. Download fresh OSM data for the city region (openstreetmap.org or Geofabrik)
2. Re-process: osrm-extract, osrm-partition, osrm-customize
3. Restart the OSRM server with the new data
4. On the 1st of the next month (or immediately via POST /grid/admin/osrm-refresh),
   OsrmMatrixRefreshJob re-queries OSRM and replaces the tile_travel_time rows in the DB.
```

**Recommendation for Indian cities:** Geofabrik publishes updated India `.osm.pbf` extracts roughly monthly. Tie the OSRM data update + server restart to a monthly ops runbook, then the scheduled job picks it up automatically.

---

## 2. GridInitializationJob

**File:** `grid/src/main/java/com/oneday/grid/batch/GridInitializationJob.java`

**Triggered by:** `POST /api/grid/admin/init?cityCode=delhi` (REST API)

**Runs:** Once per city, at city onboarding time. Never re-runs after that (GridServiceImpl will throw if the city already has a grid row).

### What it does (step by step)

```
1. Calls GridService.initializeGrid(cityId, cityCode)
   └── Reads classpath:serviceability/{cityCode}.yaml
       └── Contains: city center lat/lon, list of serviceable pincodes with lat/lon
   └── Computes tileDeltaLat and tileDeltaLon from the 2km tile size
       (tileDeltaLat = 2.0 km / 111.32 km per degree)
       (tileDeltaLon = 2.0 km / (111.32 × cos(centerLat)) — adjusts for latitude)
   └── Finds bounding box: min/max lat and lon across all pincodes, padded by one tile
   └── Inserts Grid row (origin corner + tile deltas)
   └── Inserts all M×N Tile rows (initially inactive)
   └── Maps each pincode to its tile → sets that tile + its 4 cardinal neighbours to active
       (1-tile geometric buffer handles pincodes at tile edges)
   └── Inserts PincodeMapping rows
   └── Inserts (M+1)×(N+1) GridVertex rows (lattice corners for M6 van routing)
   └── Puts the grid into the in-memory gridCache (keyed by cityId)

2. Calls OsrmMatrixRefreshJob.refresh(cityId)
   └── (see §3 below)
```

### Why the buffer?

A pincode's lat/lon is its centroid. Customers in that pincode may actually live in an adjacent tile. Activating the 4 cardinal neighbours ensures a DA always has enough tiles to cover the pincode's real coverage area. It's a one-ring buffer, not a chain: only immediate neighbours of pincode-mapped tiles are activated, not neighbours of neighbours.

### Dependencies

- `GridService` (creates all DB rows)
- `OsrmMatrixRefreshJob` (computes initial travel times right after grid creation)
- OSRM server must be reachable for the initial OSRM query. If OSRM is down at init time, the job will fail. Retry by calling `POST /grid/admin/osrm-refresh` once OSRM is back up.

---

## 3. OsrmMatrixRefreshJob

**File:** `grid/src/main/java/com/oneday/grid/batch/OsrmMatrixRefreshJob.java`

**Triggered by:**
- `@Scheduled(cron = "0 0 2 1 * *")` — 02:00 IST on the 1st of every month (all cities)
- Automatically by `GridInitializationJob` (at city creation)
- Note: `NightlyReplanJob` no longer triggers OSRM refresh directly — `GridReplanServiceImpl` uses geometric fallback when the matrix is absent/stale rather than triggering a refresh at replan time

### What it does (step by step)

```
1. Gets the Grid record for the city (to obtain gridId)

2. Calls OsrmMatrixService.computeAdjacencyMatrix(cityId)
   └── Loads all active tiles for the city
   └── Builds a list of tile centroids: lat = originLat + (row + 0.5) × tileDeltaLat
                                         lon = originLon + (col + 0.5) × tileDeltaLon
   └── Sends one OSRM /table/v1/driving request with all centroids
       → OSRM returns an N×N matrix of travel seconds between every pair of tiles
   └── Filters: keep only pairs where travelTime ≤ adjacency_threshold_seconds (default 600s = 10 min)
   └── Returns: Map<fromTileId, List<TileEdge(toTileId, travelTimeSec)>>
   └── Logs isolation warnings for tiles with zero road-reachable neighbours

3. Calls OsrmMatrixService.computeTraversalCaps(cityId)
   └── For each active tile, sends an OSRM /route/v1/driving call from SW corner → NE corner
   └── Returns: Map<tileId, traversalCapSeconds>
   └── traversalCapSec is used by DemandScoringService as a winsorisation cap for inter-stop travel
       (a DA can't spend more time driving across a tile than it takes to cross it diagonally)

4. Deletes all existing tile_travel_time rows for this city's grid (bulk DELETE, not append-only)
   tile_travel_time is the one table in M3 that is replaced wholesale on refresh.

5. Inserts new TileTravelTime rows (gridId, fromTileId, toTileId, travelTimeSec, computedAt)
   Only pairs within the 10-minute threshold are stored.
   A typical city with 50 active tiles produces ~50×50 = 2500 pairs, filtered to ~200–400 within threshold.

6. Updates tile.traversal_cap_sec for each active tile.
   This enables the DemandScoringService's inter-stop winsorisation to kick in.

7. Logs: total edges persisted, tiles updated, isolated tiles.
```

### What "isolated" means

If tile T has zero OSRM-reachable neighbours after the threshold filter, it means no other active tile is within 10 minutes by road. In practice this happens when:
- A tile is geographically separated (island, far-out industrial zone)
- A tile was activated only because it's adjacent to a pincode tile, but no roads connect it to others

Isolated tiles still get assigned a DA (BFS gives each isolated tile its own DA territory). The `ISOLATION_WARNING` log is informational — it tells you the DA assigned to that tile will only service that one tile, which may indicate the pincode mapping needs review.

### OSRM data staleness check (in NightlyReplanJob)

`NightlyReplanJob` checks the `computedAt` timestamp on stored `tile_travel_time` rows. If the oldest row is > 45 days old (or if there are no rows at all), it triggers `OsrmMatrixRefreshJob.refresh()` before running the solver. This is a safety net — the scheduler should have already run it monthly, but this catches cases where the scheduler failed or the city was never initialised.

---

## 4. NightlyReplanJob

**File:** `grid/src/main/java/com/oneday/grid/batch/NightlyReplanJob.java`

This is the core of M3. It runs once per night per city and produces the `AssignmentProposal` that the station manager reviews and approves before the morning shift.

### Three scheduled methods

| Method | Cron | Time (IST) | Purpose |
|--------|------|------------|---------|
| `run()` | `0 0 1 * * *` | 01:00 | Main replan — compute and persist proposal |
| `checkEscalation()` | `0 0 6 * * *` | 06:00 | Warn if proposal not yet approved |
| `applyFallbackIfNeeded()` | `0 0 7 * * *` | 07:00 | Auto-fallback: copy yesterday's plan if still no approval |

---

### `run()` — 01:00 IST

Iterates all cities. For each city, fetches DA IDs from `DaRosterPort` and delegates all solver logic to `GridReplanService`:

```java
private void replanForCity(UUID cityId, LocalDate validForDate) {
    List<UUID> daIds = daRosterPort.getAvailableDaIds(cityId, validForDate);
    gridReplanService.replan(cityId, validForDate, daIds);
}
```

**`GridReplanServiceImpl.replan(...)` does the actual work:**

```
Step 1: Demand scoring
───────────────────────────────────────────────────────────────────────
DemandScoringService.computeAndPersistDemand(cityId, date)
→ one TileDemandSnapshot row per active tile
→ bootstrap mode (12min service time, 5min inter-stop) until M4 live

Step 2: Load adjacency graph from DB (NOT OSRM)
───────────────────────────────────────────────────────────────────────
Reads tile_travel_time rows for pairs ≤ 600s.
If absent or oldest computedAt > 45 days: falls back to geometric 4-connectivity
(|Δrow| + |Δcol| == 1). Sets adjacencySource = GEOMETRIC_FALLBACK on proposal.
Note: does NOT trigger OsrmMatrixRefreshJob — that is the monthly scheduled job's job.

Step 3: Component C — deferred
───────────────────────────────────────────────────────────────────────
Tiles exceeding DA_max_load are logged and passed to solver unchanged.
Solver flags them as understaffed. Blocked on schema solution for virtual tile IDs.

Step 4: Run CP-SAT solver (BFS fallback if infeasible/timeout)
───────────────────────────────────────────────────────────────────────
CpSatAssignmentServiceImpl → persists AssignmentProposal (PROPOSED) + regions + assignments.
Returns ProposalResponse via proposalService.getProposal(proposal.getId()).
```

---

### `checkEscalation()` — 06:00 IST

Simple check: for each city, query `AssignmentProposalRepository.findByCityIdAndValidForDateAndStatus(cityId, today, APPROVED)`. If empty, logs `ESCALATION_ALERT`. This is the signal to wake up the station manager — they have one hour before auto-fallback fires.

No state is changed. This is a read-only diagnostic.

---

### `applyFallbackIfNeeded()` — 07:00 IST

If the station manager hasn't approved a proposal by 07:00 (shift start), the system cannot leave DAs without territory assignments. The fallback copies yesterday's approved plan to today.

```
For each city:
  1. Check if today already has an APPROVED proposal → if yes, skip.
  2. Find yesterday's APPROVED proposal.
     If none: log AUTO_FALLBACK_FAILED and skip (nothing to copy from).
  3. Load all ACTIVE DaTileAssignment rows from yesterday's proposal.
  4. Create a new AssignmentProposal:
       validForDate = today
       status = APPROVED (auto-approved, no human review)
       solverType = MANUAL
       notes = "Auto-fallback: no approved proposal by 07:00; copied from {yesterday}"
  5. Copy DaTileAssignment rows (same daId, tileId, nDasOnTile) with validDate = today.
  6. Copy AssignmentProposalRegion rows from yesterday's proposal.
```

**Important invariant:** The fallback only fires if no APPROVED proposal exists for today. If the station manager approves the nightly proposal even one minute after 07:00 but before this job runs (scheduling jitter), the check will find the approved proposal and skip. The fallback is the last resort, not a replacement for manager approval.

---

## 5. IntradayMonitorJob

**File:** `grid/src/main/java/com/oneday/grid/batch/IntradayMonitorJob.java`

**Schedule:** `@Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)` — every 5 minutes, all day. Skips ticks outside 07:00–20:00 IST.

This job monitors whether tiles are becoming overloaded during the shift. It reads from an in-memory map (populated by `TileQueueDepthConsumer` via Kafka) and emits alerts when overload is sustained.

### Data flow

```
M4 (Orders)
    ↓ Kafka: orders.tile_queue_depth (per-tile event on order status change)
TileQueueDepthConsumer   [autoStartup=false until M4 ships]
    ↓ calls IntradayLoadScoreService.updateQueueDepth(cityId, date, Map.of(tileId, unservedOrders))
IntradayLoadScoreServiceImpl
    ↓ stores in ConcurrentHashMap<tileId, unservedOrders>
IntradayMonitorJob.run()
    ↓ reads getLoadScore(tileId, today) → TileLoadScoreResponse
    ↓ applies thresholds + hysteresis
    ↓ calls TileOverloadAlertProducer.emit(...)
```

`TileQueueDepthConsumer` is deployed but `autoStartup = false` — flip `grid.kafka.consumer.auto-startup: true` when M4 starts publishing. Until then `unservedOrders` is always 0, so no alerts fire.

### Threshold and hysteresis logic

```
adjustedLoadScore = unservedOrders / max(expectedOrdersByNow, 1)

Until M4 provides expectedOrdersByNow, the denominator defaults to 1,
so adjustedLoadScore == unservedOrders (raw count).

Per tick (every 5 minutes):

  if adjustedLoadScore >= overloadCriticalThreshold (default 2.0):
      severity = "CRITICAL"
      sustainedMinutes[tile] += 5
      if sustainedMinutes[tile] >= criticalSustainedMinutes (default 10):
          and lastAlertAt[tile] is > 30 minutes ago:
              → emit TileOverloadAlertProducer.emit(...)
              → update lastAlertAt[tile] = now
              → log LEVEL3_SUGGESTION_PENDING (BFS auto-rebalance not yet implemented)

  else if adjustedLoadScore >= overloadWarningThreshold (default 1.5):
      severity = "WARNING"
      sustainedMinutes[tile] += 5
      if sustainedMinutes[tile] >= warningSustainedMinutes (default 15):
          and lastAlertAt[tile] is > 30 minutes ago:
              → emit TileOverloadAlertProducer.emit(...)
              → update lastAlertAt[tile] = now

  else:
      sustainedMinutes[tile] = 0   (reset counter — below threshold)
```

**Why hysteresis?** A single spike (one bad Kafka message, a sudden burst of 3 orders) should not page the station manager. Requiring 10–15 minutes of sustained overload filters out transient noise and ensures the alert reflects a real operational problem.

**Why re-alert suppression (30 minutes)?** Once the station manager has been paged, they need time to act. Suppressing re-alerts for 30 minutes prevents a flood of duplicate pages while they're already handling the situation.

### Shift-start reset

When the clock crosses 07:00 IST, `IntradayLoadScoreService.resetForShift()` is called (clears the unserved-orders map) and the job resets `sustainedMinutes` and `lastAlertAt`. This ensures yesterday's overload state doesn't bleed into today's shift.

---

## 6. DaRosterPort and NoOpDaRosterPort

**Files:**
- `com.oneday.grid.batch.DaRosterPort` (interface)
- `com.oneday.grid.batch.NoOpDaRosterPort` (placeholder implementation)

### Why does this exist?

`NightlyReplanJob` needs to know which DAs are scheduled to work tomorrow in each city. This information lives in M1 (auth / HR roster). M1 is not yet implemented.

Rather than hardcoding an empty list inside `NightlyReplanJob`, the port pattern keeps the boundary explicit:

```
NightlyReplanJob  ──calls──►  DaRosterPort (interface)
                                     ▲
                              NoOpDaRosterPort  (today: returns empty list + WARN log)
                              RealDaRosterPort  (future: queries M1's DA schedule table)
```

When M1 ships a real implementation, annotate it `@Primary` and Spring will wire it instead of `NoOpDaRosterPort`. `NightlyReplanJob` does not change.

**Practical impact of empty DA list today:** `BfsAssignmentServiceImpl` and `CpSatAssignmentServiceImpl` both handle zero available DAs — they produce a proposal with `totalDas = 0`, all tiles flagged as understaffed in `understaffedTileIds`. This is semantically correct: the proposal says "we have no DAs, no coverage is possible". Once M1 is integrated and real DAs are registered, the proposal will show real territory assignments.

---

## 7. Kafka Events

**Package:** `com.oneday.grid.events`

All three Kafka classes are fully wired. M3's two produced events share the consolidated
`oneday.grid.events` topic (`com.oneday.common.kafka.KafkaTopics.GRID_EVENTS`), discriminated by the
`eventType` field (`com.oneday.common.kafka.enums.GridEventType`). The consumed topic name lives in
the grid-local `KafkaTopics.java`. Event payloads are Java records in `com.oneday.grid.events.payload`.

| Class | Type | Topic | eventType | Key | Emitted/consumed by |
|-------|------|-------|-----------|-----|---------------------|
| `NoDaAlertProducer` | Producer | `oneday.grid.events` | `NO_DA_ALERT` | tileId | M3 → M5, M11 |
| `TileOverloadAlertProducer` | Producer | `oneday.grid.events` | `TILE_OVERLOAD_ALERT` | tileId | M3 → M5, M10 |
| `TileQueueDepthConsumer` | Consumer | `orders.tile_queue_depth` | — | — | M4 → M3 |

Both producers inject `KafkaTemplate<String, Object>` and wrap the send in `try/catch` — if no broker is available (local dev), they log a WARN and continue. The consumer has `autoStartup = false` and is a no-op until M4 ships.

---

## 8. How the Jobs Chain Together

```
City onboarding (one-time)
───────────────────────────────────
POST /api/grid/admin/init?cityCode=delhi
    └─► GridService.initializeGrid(cityId, cityCode)
            ├─► Reads serviceability/{cityCode}.yaml
            ├─► Creates Grid, Tile, GridVertex, PincodeMapping rows
            └─► (OSRM refresh is separate — call OsrmMatrixRefreshJob manually)


Every 1st of month at 02:00 IST (steady state)
───────────────────────────────────
OsrmMatrixRefreshJob.runMonthly()
    └─► OsrmMatrixRefreshJob.refresh(cityId)  [per city]
        Only meaningful AFTER ops team has updated OSM data + restarted OSRM server.


Every night
───────────────────────────────────
01:00 IST  NightlyReplanJob.run()
    └─► per city:
        ├─► DaRosterPort.getAvailableDaIds(cityId, tomorrow)
        └─► GridReplanService.replan(cityId, tomorrow, daIds)
                ├─► DemandScoringService.computeAndPersistDemand(...)
                ├─► Load tile_travel_time from DB → adjacency graph
                │       if absent/stale ──► geometric 4-connectivity fallback
                └─► CpSatAssignmentServiceImpl.computeProposal(...)
                        ├── CP-SAT solve with lazy-cuts contiguity
                        └── if failed ──► BfsAssignmentServiceImpl.computeProposal(...)
                    → persists AssignmentProposal (PROPOSED) + regions + DaTileAssignments

    ← station manager reviews → POST /api/proposals/{id}/approve

06:00 IST  NightlyReplanJob.checkEscalation()
    └─► if no APPROVED proposal: log ESCALATION_ALERT

07:00 IST  NightlyReplanJob.applyFallbackIfNeeded()
    └─► if still no APPROVED proposal: copy yesterday's plan → new APPROVED proposal


During shift (07:00–20:00 IST, every 5 minutes)
───────────────────────────────────
M4 ──Kafka: orders.tile_queue_depth──► TileQueueDepthConsumer  (autoStartup=false until M4 ships)
    └─► IntradayLoadScoreService.updateQueueDepth(cityId, date, {tileId → unservedOrders})

IntradayMonitorJob.run()
    └─► per tile: getLoadScore → check thresholds → hysteresis → TileOverloadAlertProducer.emit(...)


On-demand (map demo / station manager UI)
───────────────────────────────────
POST /api/grid/{cityCode}/replan   body: { daIds: [...], date: "2026-05-25" }
    └─► GridReplanService.replan(cityId, date, daIds)   (same service as nightly job)
```

---

## 9. What Is Not Yet Implemented

| Gap | Where it's needed | Blocked on |
|-----|------------------|------------|
| Component C (multi-DA tile virtual splitting) | GridReplanServiceImpl | Schema change needed: `DaTileAssignment.tileId` is `updatable=false`, so virtual tile UUIDs can't be patched back after solve |
| Real DA roster from M1 | DaRosterPort | M1 (auth) — when ready, annotate impl `@Primary`; `NightlyReplanJob` does not change |
| TileQueueDepthConsumer active | IntradayMonitorJob data source | M4 (Orders) publishing `orders.tile_queue_depth` — flip `grid.kafka.consumer.auto-startup: true` when ready |
| Station manager push notification at 01:00 | NightlyReplanJob | Station manager UI (React) — Phase 8 API exists, notification channel TBD |
| `@EnableScheduling` on app entry point | All `@Scheduled` methods | `app/` module not yet started |
| Integration tests (Phase 9) | Full end-to-end validation | TestContainers + WireMock for OSRM |
