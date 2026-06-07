# M6 — Implementation Plan

| Field | Value |
|-------|-------|
| **Module** | `routing` (`com.oneday.routing`), artifactId `routing` — depends on `common`, `grid` |
| **Design doc** | [`docs/M6/M6-ROUTING-DESIGN.md`](./M6-ROUTING-DESIGN.md) (v0.3) — read it first; this plan implements it |
| **Status** | Not started — `routing/` is an empty skeleton (pom only) |
| **Shape** | **8 PRs** in 3 parts: plan-time (PR 1–4), execution + run-time (PR 5–7), simulation/E2E (PR 8) |

---

## How to use this plan

- Work top-to-bottom. Each **PR** is independently reviewable and (from PR #1 on) leaves `mvn clean install -pl routing` green. PRs are deliberately chunky — a coherent capability each, not a micro-slice.
- Every PR cites the design-doc section (`§n`) and decision (`M6-D-xxx`) it implements.
- **M2, M7, M8, M9 are unbuilt.** M6 builds and tests end-to-end *now* behind stub ports (`CostFloorPort`, `FlightCutoffPort`, `HubSortPort`, `DaAccumulationPort`, `ScanLedgerPort`); real impls swap in later without touching the optimiser or manifest engine (mirrors M3's `DaRosterPort`).
- **Package layout** (`CLAUDE.md`): `api/` · `service/` (+ `service/impl/`, `service/port/`) · `domain/` · `repository/` · `events/` (+ `events/payload/`) · `dto/` · `batch/` · `config/`. Cross-module rule: import only another module's **public service interface**, never internals.

---

# Part I — Plan-time

## PR #1 — Foundations: contracts, schema, domain, ports, clock

Implements design §17 (contracts), §6 (seams), `M6-D-007/-010/-013`. *Clubs the old "shared contracts" + "DB & domain" phases.*

### Read first
`common/kafka/KafkaTopics.java` (`CRON_EVENTS` reserved `// M6`), `common/kafka/enums/GridEventType.java` (discriminator convention), `grid/db/migration/V3_*` (Flyway style), `grid/domain/*` + `grid/config/GridProperties.java` (entity + properties style).

### What to build
1. **`common.kafka.enums.CronEventType`** — 10 values (§17.1) + record payloads (`CronEventBase` + concrete events), Jackson-serializable. *(Scans `VAN_LOAD/VAN_TO_DA/DA_TO_VAN/VAN_UNLOAD` are M8-ledger writes, not cron events.)*
2. **Stub ports** (`routing.service.port` interfaces + No-op impls, config-toggled like `grid.batch.NoOpDaRosterPort`): `CostFloorPort`, `FlightCutoffPort`, `HubSortPort`, `DaAccumulationPort`, `ScanLedgerPort`.
3. **`config.ClockConfig`** — `@Bean Clock` (IST); all services inject `Clock`, never `Instant.now()` (`M6-D-013`).
4. **`config.RoutingProperties`** (`@ConfigurationProperties("routing")`) — `osrm.baseUrl`, `solver.timeLimitSeconds`, `cycle.{min,max}Minutes`, `dwellMinutes`, `maxDaToVertexMinutes`, `shuttle.cadenceMinutes`, `window.{start,end}Hour`, `lateThresholdMinutes`, `costPerKm`.
5. **Flyway `V6_1…V6_11`** (`routing/db/migration`) — the full §17.3 DDL: `city_logistics_node`, `city_fleet_config`, `route_plan`, `route_plan_stop`, `da_cron_schedule`, `route_override_audit`, `van_manifest`, `van_manifest_item`, `handoff_reconciliation`, `van_live_status`, + `V6_11` seed (hub/airport coords + starter fleet config for all 5 cities, `ON CONFLICT DO UPDATE`, reusing `grid.cities` UUIDs). Indexes per §17.3.
6. **JPA entities + repositories + enums** for all tables (append-only ones immutable + `@PrePersist`, copying `grid` style). Enums: `LogisticsNodeKind`, `RoutePlanStatus`, `RoutePlanSource`, `RoutingSolverType`, `ProvisioningFlag`, `StopNodeKind`, `ManifestStatus`, `ManifestItemStatus`, `HandoffDirection`, `DiscrepancyType`. Key finders: `RoutePlanRepository.findByCityIdAndValidForDateAndStatus`, `RoutePlanStopRepository.findByRoutePlanIdAndVanIdAndLoopIndex`, `DaCronScheduleRepository.findByDaIdAndValidDate`, `VanManifestItemRepository.findByManifestIdAndStopSeq`, etc.

### Verify
`mvn clean install -pl common,routing`. Boot vs local Postgres → Flyway applies V6_1…V6_11 clean; 10 `city_logistics_node` rows seeded. Each `CronEventType` payload round-trips through Jackson; one JPA round-trip test per entity.

---

## PR #2 — Inputs & the optimiser core

Implements §6, §7.1–7.3, §3, `M6-D-001/-002/-006/-009`. *Clubs input adapters + meeting-point selection + the VRP solver.*

### Read first
`grid/service/GridService.java` (the **only** grid surface M6 may use), `grid/service/osrm/OsrmClient.java` (pattern to copy, not import), `grid/service/impl/CpSatAssignmentServiceImpl.java` (OR-Tools native-lib loading).

### What to build
1. **`service.GridDataAdapter`** — territories / vertices / per-hex demand via `GridService` **public interface only** (no `grid.domain`/`grid.repository` imports). *Blocker: may need a new `GridService` method exposing DA→hexes→vertices — see Open Blockers.*
2. **`service.osrm.RoutingOsrmClient`** + **`service.TravelMatrixService`** — M6's own thin OSRM `/table` client over its node set `{hub} ∪ vertices (∪ airport)`; build + cache the matrix per run (`M6-D-009`).
3. **`service.DemandAggregationService`** — per ACTIVE DA aggregate `demand_score_orders` + `service_time_min` → `TerritoryDemand` (symmetric split v1, Q3).
4. **`service.MeetingPointSelectionService`** + impl — weighted greedy set-cover (`M6-D-001`): fewest vertices covering all territories, biased to high-degree shared vertices, bounded by `maxDaToVertexMinutes`. Output `MeetingPlan{vertices, vertexToDaIds, daToVertex}`.
5. **`service.VanRouteSolver`** + `impl.OrToolsVanRouteSolver` — `RoutingModel`: vehicles start/end at hub; **capacity dimension with peak-load** ≤ `capacity_packets` (`M6-D-002`); **time dimension** span ≤ `cycle_max`; cost = travel time (cost-floor-weighted when M2 lands); `PATH_CHEAPEST_ARC` + `GUIDED_LOCAL_SEARCH`. Plus **`impl.SavingsVanRouteSolver`** (Clarke–Wright fallback) + **`VanRouteSolverSelector`** (OR-Tools, fall back on missing native lib / timeout — mirrors M3's BFS fallback).

### Verify
`mvn test -pl routing` (tag OR-Tools tests; CI without native lib runs the savings path, copying M3's `UnsatisfiedLinkError` handling). Tests: matrix built vs mocked OSRM; **no `import com.oneday.grid.domain`** (grep/ArchUnit check); set-cover covers all territories & prefers a shared 3-territory vertex; capacity never exceeded at any route point; span ≤ cycle_max.

---

## PR #3 — Plan assembly & fleet sizing

Implements §7.4–7.5, §8, `M6-D-003/-005/-008`. *Clubs periodise+persist + the fleet recommendation pass.*

### Read first
`grid/service/impl/GridReplanServiceImpl.java` (orchestrate + persist pattern), design §7.4–7.5, §8.

### What to build
1. **`service.RoutePlanningService`** + impl — orchestrate Stage 1→4: demand → meeting points → matrix → solve → **periodise** (`n_loops = floor(window/cycle)`, stamp wall-clock ETAs per (van, loop, stop), `M6-D-003`). Persist `route_plan` (PROPOSED) + all `route_plan_stop` rows + derive `da_cron_schedule` `(vertex,[meetingTimes])` (`M6-D-008`). Assert **C6** (spacing ≥ M5 `CRON_FREEZE_MINUTES`).
2. **Fleet sizing** — second solver pass with OR-Tools free to add vehicles at high fixed cost ⇒ `recommended_van_count`; set `UNDER_PROVISIONED` + station-manager notification when `vans_available < recommended` (`M6-D-005`).

### Verify
End-to-end (Testcontainers + mocked OSRM): `plan(cityId, date)` writes a coherent PROPOSED plan; one `da_cron_schedule` per active DA with ≥1 meeting time; loop ETAs monotonic, spaced ≥ 30 min. A 3-van-need / 2-van-config city → plan produced, flagged `UNDER_PROVISIONED`, recommended = 3.

---

## PR #4 — Nightly governance & shuttle

Implements §10, §9, `M6-D-008`. *Clubs the nightly job + approval/override/audit + producers + shuttle. Completes Part I.*

### Read first
`grid/batch/NightlyReplanJob.java` (01:00/06:00/07:00 cadence + `applyFallback`), `grid/api/ProposalController.java` (approve flow), §9–§10.

### What to build
1. **`batch.NightlyRoutePlanJob`** — `@Scheduled` 01:00 IST → `RoutePlanningService.plan` per city (PROPOSED for tomorrow); 06:00 escalation; 07:00 fallback (copy yesterday's APPROVED forward — port `applyFallback`).
2. **`api.RoutePlanController`** — `approve` (city-scoped via M1), `override` (append-only `route_plan` revision + `route_override_audit`), `replan`, and the `GET` plan/stops/cron/`cron/.../next`/shuttle endpoints (§17.2).
3. **`events.CronEventProducer`** — `DA_CRON_SCHEDULED` on approve, `ROUTE_PLAN_PUBLISHED`, `ROUTE_CHANGED` on override.
4. **`service.ShuttleScheduleService`** — periodic hub↔airport timetable from `shuttle.cadenceMinutes` + OSRM hub↔airport time → `SHUTTLE_SCHEDULED`; `GET /routing/shuttle/{cityId}`.

### Verify
Job test with fixed `Clock`: 01:00 → PROPOSED; unapproved by 07:00 → fallback copies prior plan. Controller: approve emits `DA_CRON_SCHEDULED`; override writes audit + emits `ROUTE_CHANGED`; cross-city approve rejected. Shuttle: cadence 30 min over 07:00–20:00 → expected departures + arrival ETAs.

---

# Part II — Execution & run-time

## PR #5 — Manifest engine & parcel→loop binding

Implements §11.2, §12, `M6-D-015/-016/-017`. *Clubs deliver + collect binding + overflow.*

### Read first
§11.2 (manifest), §12 (binding); `HubSortPort`, `DaAccumulationPort`, `FlightCutoffPort` stubs.

### What to build
1. **`service.VanManifestService`** + impl:
   - **Deliver** — on `HubSortPort` "ready to load": destination hex → DA → vertex + van; delivery deadline (M4 SLA stub); bind to **earliest deadline-feasible loop with capacity** (`M6-D-016`); append `van_manifest_item(DELIVER, …, PLANNED)`.
   - **Collect** — from `DaAccumulationPort`: hub-arrival deadline = cutoff − (sort+bag+shuttle) tail (`FlightCutoffPort`, §12.2) → latest feasible collect loop.
   - Manifest lifecycle BUILDING→LOADED→IN_PROGRESS→RETURNED→RECONCILED.
2. **Overflow** (`M6-D-017`) — loop load > capacity ⇒ bump latest-deadline items to next feasible loop; none before deadline ⇒ emit `LOOP_OVERFLOW` (station mgr + M10), recommend ad-hoc van. Never silent-drop.

### Verify
Unit: SLA-first ordering (tight parcel jumps a slack one); overflow bumps then escalates; no-feasible-loop ⇒ `LOOP_OVERFLOW`, never a drop.

---

## PR #6 — Custody scans, handoff & failure handling

Implements §11.1, §13, §16, `M6-D-014/-018/-019/-021`. *Clubs the 4 custody scans + handoff protocol + failures/recovery.*

### Read first
§11.1 (4 scans + RACI), §13 (handoff + failures), §16 (boundaries); `ScanLedgerPort` stub.

### What to build
1. **`service.CustodyService`** — the 4 transfer points (LOAD/DELIVER/COLLECT/UNLOAD): validate vs manifest, write scan via `ScanLedgerPort` (M8), advance `van_manifest_item.status`, emit the M4 transition signal (`HANDED_TO_DROP_VAN`/`DROP_COLLECTED`/`HANDED_TO_PICKUP_VAN`/`AT_ORIGIN_HUB`). Enforce **C12** (custody continuity).
2. **`service.HandoffService`** — per stop: open dwell window (`dwellMinutes`); per-DA reconcile expected (manifest) vs scanned; write `handoff_reconciliation`; emit `HANDOFF_COMPLETED` or `HANDOFF_DISCREPANCY` (MISSING/EXTRA/REJECTED → M11 + M10). Partial handoff legal (`M6-D-018/-019`).
3. **Failures** (`M6-D-021`) — DA no-show (carry deliveries → rebind next loop; defer collections; escalate if deadline at risk); van breakdown (`POST /routing/vans/{vanId}/recovery` → reassign manifest to recovery van, recompute deadlines, rebind; emit `VAN_BREAKDOWN`); mis-sort (carry back to hub sort, rebind).

### Verify
Full chain on one parcel (LOAD→DELIVER, COLLECT→UNLOAD): statuses progress; unmatched scan → `HANDOFF_DISCREPANCY` → M11. Late-DA → partial handoff + rebind; breakdown → manifest reassigned + `VAN_BREAKDOWN` (recovery is the only sanctioned intraday fleet change, C18).

---

## PR #7 — Telemetry, live tracking & driver-app contract

Implements §14, §15, `M6-D-011/-012/-020`. *Clubs ingestion + deviation + driver endpoints + Kafka wiring.*

### Read first
§14 (telemetry, in-process), §15 (driver workflow), §16 (consumers).

### What to build
1. **`api.VanTelemetryController`** — `POST /api/v1/van/{vanId}/telemetry` (GPS + DELIVER/COLLECT scans; `type` discriminates) → calls `VanTrackingService` directly (no Kafka for raw pings, `M6-D-012`).
2. **`service.VanTrackingService`** — overwrite `van_live_status`; scans → `CustodyService`/`HandoffService`; `ARRIVED_AT_STOP` → lateness vs plan, propagate to remaining stops, emit `VAN_ARRIVED` / `VAN_RUNNING_LATE` via **`events.RouteDeviationProducer`** (`M6-D-011`).
3. **Driver-app endpoints** (`M6-D-020`) — `GET /routing/vans/{vanId}/manifest?loop=`, load-scan, stop-confirm, return-scan, `GET /routing/vans/{cityId}/live`.
4. Wire all producers.

### Verify
Load test ~10 req/s × 100 virtual vans — in-process path, no Kafka per ping. Slow leg → `VAN_RUNNING_LATE` + corrected ETAs. Full loop via driver endpoints → manifest BUILDING→…→RECONCILED; M4 transition signals in order; mock M5/M7/M8 consumers receive the right events / `ScanLedgerPort` writes.

---

# Part III — Simulation

## PR #8 — Simulation harness & end-to-end

Implements §21, `M6-D-013`.

### Read first
§21 (sim), `M6-D-013` (accelerated clock).

### What to build
- Test-only `simulator`: **virtual van** (drives a plan, POSTs telemetry + scans along OSRM geometry on the accelerated `Clock`), **virtual hub** (`HubSortPort` feed), **fault injector** (no-show, breakdown, mis-sort, demand spike) — public APIs/events only.
- **Virtual-day E2E** on a seeded city: assert invariants (no cron missed unless injected, C12 custody continuity, overflow escalates not drops, van-late propagates) and calibrate `maxDaToVertexMinutes`, `cycle`, `dwellMinutes`, overflow threshold (Q4/Q5/Q9/Q10).

### Verify
`mvn test -pl routing` runs a full simulated day in seconds. Each fault-library case asserts the right module reaction.

---

## PR Dependency Graph

```
#1 foundations (contracts + schema + domain + ports + clock)
      │
#2 inputs + optimiser (adapters/matrix + set-cover + VRP/fallback)
      │
#3 plan assembly + fleet sizing
      │
#4 nightly governance + shuttle        ◄── end of Part I (plan-time)
      │
#5 manifest engine + binding
      │
#6 custody scans + handoff + failures
      │
#7 telemetry + live tracking + driver app
      │
#8 simulation + virtual-day E2E
```
Strictly linear — each PR builds on the last; no parallel branches to track.

---

## Open blockers — raise before the PR that needs them

| Blocker | Needed by | Action |
|---------|-----------|--------|
| **M3 exposes DA→hexes→vertices via public `GridService`** | PR #2 | Raise with M3 owner now — longest-lead dependency; may need a new `GridService` method. |
| **M5 cron-schedule format** — M5 holds one meeting time; M6 emits the day's list (`M6-D-008`) | PR #4 | Agree: M5 stores list / picks next, or uses `GET /routing/cron/da/{daId}/next`. |
| **M7 `HubSortPort` contract** — "ready to load for delivery" shape | PR #5 | Stub now; agree real contract when M7 starts. |
| **M9 `FlightCutoffPort` + the §12.2 end-to-end tail budget (Q2)** | PR #5 | Stub now; Q2 is cross-module (M7/M9/M10) — open early. |
| **M8 `ScanLedgerPort`** — is the van a new scan-location type? (Q12) | PR #6 | Confirm with M8 owner; stub writes locally until M8 exists. |
| **M4 custody state names** (`HANDED_TO_DROP_VAN` etc.) | PR #6 | Confirm with M4 owner (already in M5's design). |
| **Driver vs van identity on scans (Q14)** | PR #6 | Confirm with M1 — put both on each scan. |
| **OSRM in CI** | PR #2+ | Mock `/table` in tests; document a self-hosted OSRM URL for E2E. |

---

*Plan v0.2 — implements design doc v0.3 in **8 PRs**. Part I plan-time = PR 1–4; Part II execution + run-time = PR 5–7; simulation/E2E = PR 8. Everything builds and tests now behind stub ports; M2/M7/M8/M9 real impls swap in without touching the optimiser or manifest engine.*
