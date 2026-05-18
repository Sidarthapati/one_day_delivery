# M5 — Implementation Plan

| Field | Value |
|---|---|
| **Module** | M5 — Dispatch |
| **Plan version** | 1.0 |
| **Author** | Design session 2026-05-19 |
| **Last updated** | 2026-05-19 |
| **Total PRs** | 15 |
| **Design doc** | [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) |

---

## Architectural Principles

**1. Dependency questions before implementation.**
M5 has 21 open interface questions for M1, M3, and M4 (see [M5-DEPENDENCY-QUESTIONS.md](M5-DEPENDENCY-QUESTIONS.md)). Phase 0 and Phase 1 can proceed without answers. Phase 3 onward requires answers to at minimum Q-M1-4 (internal service token), Q-M3-1 (are M3 REST controllers live), Q-M4-1 (`originTileId` always non-null), Q-M4-5 (delivery verification mechanism OD-8).

**2. Common contracts before any M5 business logic.**
`DaEventBase`, the 5 missing `DaEventType` values, and the `DISPATCH_TILE_QUEUE_DEPTH` topic constant must land in `common` before M5 writes a single producer or consumer. Phase 0 is a `common`-only PR that unblocks M10 and M11 as well.

**3. Algorithm correctness is non-negotiable before Kafka wiring.**
The cheapest-insertion heuristic and cron feasibility service are the load-bearing core of M5. They must be exhaustively unit-tested in isolation (Phase 3) before being wired to Kafka event consumption (Phase 4). A broken assignment engine with live Kafka consumers would cause silent mismatch between DB state and M4's state machine.

**4. In-memory state is the hot path; DB is the audit trail.**
Every mutation is written to DB, but correctness of feasibility checks and queue ordering depends on the in-memory `DaQueue` state, not DB reads. Tests must verify that the in-memory and DB views stay consistent after concurrent inserts, restarts, and GPS flushes.

**5. Per-DA `ReentrantLock` must be in place before any concurrent assignment test.**
Two simultaneously arriving orders for the same DA must not produce stale feasibility decisions. The lock must be acquired before cheapest-insertion begins and released only after the DB write commits. This is tested explicitly in PR #7.

**6. ConfigurationProperties for every threshold.**
`CRON_FREEZE_MINUTES`, `ABSENT_THRESHOLD_MINUTES`, `ROAD_FACTOR`, `AVG_SPEED_KMPH`, `OSRM_CONFIRM_THRESHOLD`, GPS flush interval, shift times — all must be `@ConfigurationProperties`-bound from day one. No hardcoded literals.

---

## Dependency Flow

```
Phase 0 (Common contracts)
    │
    ├──► M10 team can consume DaEvent(PICKUP_COMPLETED), DaEvent(VAN_HANDOFF_COMPLETED)
    ├──► M11 team can produce ExceptionsEvent(PICKUP_RESCHEDULED / DELIVERY_RESCHEDULED)
    │
Phase 1 (DB + Domain)
    │
Phase 2 (Shift infrastructure: GPS, DA status, shift load/end jobs)
    │
Phase 3 (Core engine: cheapest-insertion + cron feasibility) ← needs Q-M1-4 answered
    │
Phase 4 (Kafka consumers) ← needs Q-M4-1, Q-M3-1 answered
    │
Phase 5 (DA-facing APIs: GPS, OTP, van handoff, delivery lifecycle) ← needs Q-M4-5 answered
    │
Phase 6 (Station manager view + cross-territory dispatch)
    │
Phase 7 (Scheduled jobs: tile_queue_depth publisher, deferred retry)
    │
Phase 8 (Resilience + observability)
```

---

## Phase 0 — Shared Contracts
> **Target: `common` module. Unblocks M10, M11, and M5's own Kafka wiring.**

---

### `M5 IMPL - PR #1 - DaEventBase, DaEventType additions, and DISPATCH_TILE_QUEUE_DEPTH topic in common`

**Module:** `common`

**Why first:** M10 consumes `PICKUP_COMPLETED` and `VAN_HANDOFF_COMPLETED` for SLA leg tracking. M11 needs `TASK_DEFERRED_SHIFT_ENDED` to trigger rescheduling. Neither can be implemented until the event types exist as shared constants in `common`. Separately, `TileQueueDepthPublisher` (M5 → M3) needs a topic constant.

**What:**
- Add `DaEventBase` abstract class to `common.kafka.events`:
  ```java
  public abstract class DaEventBase {
      UUID eventId;
      String eventType;          // DaEventType.name()
      String schemaVersion = "1.0";
      Instant occurredAt;
      UUID shipmentId;
      String shipmentRef;
      UUID daId;
      String cityId;
  }
  ```
- Add 5 missing values to `common.kafka.enums.DaEventType`:
  - `QUEUE_REORDERED` — DA app re-renders stop list; not consumed by M4
  - `DA_ABSENT` — M10/M3 consumer; DA GPS silent > threshold
  - `CRON_MISSED` — M10 consumer; SLA breach event
  - `COD_COLLECTED` — finance consumer; not consumed by M4
  - `TASK_DEFERRED_SHIFT_ENDED` — M11 consumer; reschedule flow
- Add topic constant to `KafkaTopics.java`:
  ```java
  public static final String DISPATCH_TILE_QUEUE_DEPTH = "oneday.dispatch.tile_queue_depth";
  ```
- `@JsonIgnoreProperties(ignoreUnknown = true)` on `DaEventBase` (same forward-compat discipline as `BaseShipmentEvent`)

**Unlocks:** M10 can implement SLA leg consumers; M11 can implement reschedule flow; M5 can start writing producers.

---

## Phase 1 — Database & Domain Layer
> **Target: `dispatch` module. Pure data. No business logic.**

---

### `M5 IMPL - PR #2 - Flyway migrations for all M5 tables`

**Module:** `dispatch` (`db/migration/dispatch/`)

**What:**
- Tables: `dispatch_queue`, `da_cron_assignment`, `da_status`, `deferred_dispatch`, `da_assignment_audit`
- All indexes:
  - `dispatch_queue`: `(da_id, operating_date, status)`, `(shipment_id)`
  - `deferred_dispatch`: partial index `(city_id, status, retry_after) WHERE status = 'PENDING'`
- Unique constraints: `da_status.da_id`, `da_cron_assignment.(da_id, operating_date)`, `dispatch_queue.(da_id, shipment_id, task_type, operating_date)`
- No Java code in this PR

**Note:** SQL-only PR. Reviewer checks:
- Append-only tables (`dispatch_queue`, `da_assignment_audit`) must have no `ON DELETE CASCADE` or `ON UPDATE CASCADE`
- `da_status` is the one mutable table — verify `updated_at` trigger is present
- All `task_type`, `status`, `defer_reason`, `decision` columns use `VARCHAR` (not PG enum) per XC-D-003 — business values are still being finalised

---

### `M5 IMPL - PR #3 - JPA entities and Spring Data repositories`

**Module:** `dispatch`

**What:**
- Enums local to `dispatch`: `TaskType` (PICKUP, DELIVERY), `TaskStatus` (QUEUED, IN_PROGRESS, COMPLETED, FAILED, DEFERRED, CANCELLED), `DaStatusEnum` (OFFLINE, IDLE, IN_PROGRESS, CRON_LOCKED, AT_CRON, ABSENT), `DeferReason`, `AssignmentDecision`, `CronAssignmentStatus`
- JPA entities:
  - `DispatchQueue` (extends `BaseEntity`) — immutable after insert for audit purposes; status updated via `@Column(name = "status")` only
  - `DaCronAssignment` (extends `BaseEntity`) — status + completion fields are mutable
  - `DaStatus` (extends `MutableBaseEntity`) — fully mutable; GPS + status updated frequently
  - `DeferredDispatch` (extends `BaseEntity`) — status mutable; retry_after mutable
  - `DaAssignmentAudit` (extends `BaseEntity`) — fully append-only
- In-memory value objects (not JPA entities):
  - `DaQueue` — holds ordered `List<DispatchTask>` + cron schedule reference; not persisted
  - `DaLiveStatus` — holds latest GPS, heartbeat timestamp, shift status; flushed to DB every 2 min
- Spring Data repositories with custom queries:
  - `DispatchQueueRepository`: `findByDaIdAndOperatingDateAndStatusIn`, `findByShipmentId`, `findByDaIdAndOperatingDateOrderByQueuePosition`
  - `DaCronAssignmentRepository`: `findByDaIdAndOperatingDate`, `findByOperatingDateAndCityId`
  - `DaStatusRepository`: `findByDaId`, `findByCityIdAndShiftDateAndStatusIn`
  - `DeferredDispatchRepository`: `findPendingForRetry` (uses the partial index)
  - `DaAssignmentAuditRepository`: `findByShipmentId`
- `@DataJpaTest` integration tests for all custom queries

---

## Phase 2 — Shift Infrastructure
> **In-memory state initialisation, GPS handling, shift lifecycle.**

---

### `M5 IMPL - PR #4 - DaStatusService, GPS heartbeat, and in-memory state`

**Module:** `dispatch`

**What:**
- `DispatchProperties` (`@ConfigurationProperties("dispatch")`):
  - `cron.freeze-minutes = 30`
  - `da.absent-threshold-minutes = 15`
  - `gps.heartbeat-interval-seconds = 30`
  - `gps.flush-interval-seconds = 120`
  - `osrm.confirm-threshold-minutes = 20`
  - `travel.road-factor = 1.4`
  - `travel.avg-speed-kmph = 25`
- `DaStatusService` interface + `DaStatusServiceImpl`:
  - `initShift(daId, cityId, shiftDate, shiftType, cronAssignment)` — creates `DaLiveStatus` in `ConcurrentHashMap`
  - `updateGps(daId, lat, lon, timestamp)` — updates in-memory; marks row dirty
  - `updateStatus(daId, newStatus)` — acquires `daLocks.get(daId)`, updates in-memory + DB synchronously
  - `flushDirtyStatuses()` — `@Scheduled(fixedDelay=...)` batch UPDATE of all dirty rows
  - `getStatus(daId)` — reads in-memory (never DB) for hot path
- `ShiftLoadJob` (`@Scheduled` at shift_load_time − 15 min):
  - Calls `GET /grid/assignments?city_id={}&date={}` (M3)
  - Reads `da_cron_assignment` for today (populated by M6 nightly plan event)
  - Reads `tile_demand_snapshot.serviceTimeMin` for each tile (M3 shared repository or REST endpoint — pending Q-M3-5)
  - Initialises `ConcurrentHashMap<UUID, DaQueue>`, `ConcurrentHashMap<UUID, ReentrantLock>`, `ConcurrentHashMap<UUID, DaLiveStatus>`
- Unit tests: GPS flush correctly batches; flushDirtyStatuses skips clean rows; initShift idempotent on restart

---

### `M5 IMPL - PR #5 - AbsentDaDetectionJob and ShiftEndJob`

**Module:** `dispatch`

**What:**
- `AbsentDaDetectionJob` (`@Scheduled` every 5 minutes):
  - Scans in-memory `DaLiveStatus` entries for current shift
  - For each DA where `now − lastHeartbeat > absentThresholdMinutes` and status ≠ ABSENT/OFFLINE:
    - `DaStatusService.updateStatus(daId, ABSENT)`
    - Publish `DaEvent(DA_ABSENT)` via `DaEventProducer`
    - Log for station manager escalation (M10 picks up via Kafka)
- `ShiftEndJob` (`@Scheduled` at shift_end_time):
  - For each DA with QUEUED tasks:
    - Set all QUEUED `DispatchQueue` rows to DEFERRED
    - Write `DeferredDispatch` row (reason=SHIFT_ENDED) for each
    - Publish `DaEvent(TASK_DEFERRED_SHIFT_ENDED)` for each → M11 reschedule flow
  - Set all DA statuses to OFFLINE
  - Clear in-memory queues and status maps
  - Flush final `da_status` snapshot to DB
- Unit tests: absent detection skips OFFLINE DAs; shift-end defers only QUEUED tasks (not IN_PROGRESS); shift-end is idempotent (safe to run twice if pod restarts at boundary)

---

## Phase 3 — Core Assignment Engine
> **The algorithmic heart of M5. Exhaustive unit tests before Kafka wiring.**

---

### `M5 IMPL - PR #6 - CronFeasibilityService: Haversine fast path + OSRM slow path`

**Module:** `dispatch`

**Dependency:** Requires Q-M1-4 (internal service token) to be answered before wiring the OSRM call (which also acts as a proxy for the security model that will apply to M4 OTP calls).

**What:**
- `CronFeasibilityService` interface:
  - `checkFeasibility(daId, proposedQueue, newPickup, cronAssignment): FeasibilityResult`
  - `FeasibilityResult`: `{ feasible: boolean, bestInsertionIndex: int, cronSlackSeconds: int, extraTravelSeconds: int, usedOsrm: boolean }`
- `CronFeasibilityServiceImpl`:
  - Fast-path: Haversine × `ROAD_FACTOR` ÷ `AVG_SPEED_KMPH` → seconds
  - Cheapest-insertion loop over all positions (§9.1 algorithm)
  - OSRM slow-path: if best-candidate `cronSlack < OSRM_CONFIRM_THRESHOLD`, call `OsrmClient.getRoute(waypoints)` for confirmation
  - Service time: reads from in-memory tile service-time map (loaded at shift start by `ShiftLoadJob`)
  - IN_PROGRESS handling: P_0 set to in-progress task pickup address; T_current set to `expected_eta`
- `OsrmClient` reuse: wire the same `OsrmClient` bean from `com.oneday.grid.service` (M3 dependency). M5 uses the point-to-point route endpoint, not the matrix endpoint.
- Unit tests:
  - Empty queue: feasibility depends solely on travel from GPS to pickup + pickup to cron
  - Single-item queue: two insertion positions; verify minimum extra travel selected
  - All-infeasible: no valid insertion exists → `feasible = false`
  - Borderline: fast-path says feasible but within OSRM threshold → OSRM called → result overrides
  - OSRM down: circuit breaker open → falls back to fast-path with `ROAD_FACTOR × 1.2` safety buffer
  - Concurrent: two inserts under per-DA lock produce correct sequential feasibility (not stale)

---

### `M5 IMPL - PR #7 - DispatchServiceImpl: cheapest-insertion + deferred dispatch + per-DA lock`

**Module:** `dispatch`

**What:**
- `DispatchService` interface:
  - `assignPickup(shipmentId, cityId, pickupLat, pickupLon, originTileId, paymentMode): AssignmentResult`
  - `assignDelivery(shipmentId, cityId, destLat, destLon, destTileId): AssignmentResult`
  - `cancelTask(shipmentId, taskType)`
  - `reassignDeferred(deferredId)`
- `DispatchServiceImpl` (package-private):
  - `assignPickup`: tile → DA lookup → CRON_LOCKED check → `CronFeasibilityService.checkFeasibility` → insert `DispatchQueue` row → update in-memory `DaQueue` → write `DaAssignmentAudit` row → return `AssignmentResult`
  - Per-DA `ReentrantLock` acquired before feasibility check; released after DB commit
  - If `feasible = false` for all DAs in tile: write `DeferredDispatch` row; return `DEFERRED`
  - Cross-territory path (§10): triggered only when primary DA infeasible AND `loadScore >= 1.5` (calls M3 load-score endpoint)
  - `DeferredRetryService` interface + impl: reads `DeferredDispatch` where `status=PENDING AND retry_after <= now()`; attempts `assignPickup` again; updates row to ASSIGNED or leaves PENDING
- Unit tests:
  - Concurrent lock test: two threads arrive simultaneously for the same DA; second sees queue with first order already inserted
  - CRON_LOCKED guard: order is deferred immediately without feasibility check
  - Deferred retry: row flips to ASSIGNED after retry succeeds; stays PENDING if DA still infeasible
  - Cross-territory: only triggered when both conditions met; not triggered when only one is met
  - Audit row written for every decision path (ASSIGNED, CROSS_TERRITORY_ASSIGNED, DEFERRED_*)

---

## Phase 4 — Kafka Consumers
> **Wire assignment engine to the event bus. Consumers must be idempotent.**

---

### `M5 IMPL - PR #8 - ShipmentCreatedConsumer (pickup assignment)`

**Module:** `dispatch`

**Dependency:** Requires Q-M4-1 (`originTileId` nullability) answered. If null is possible, fallback to M3 `tile-at` REST call must be implemented here.

**What:**
- `ShipmentCreatedConsumer` (consumer group `m5-shipment-created-consumer`): subscribes to `oneday.shipments.events`
  - Routes on `event_type = CREATED AND pickupType = DA_PICKUP` → `DispatchService.assignPickup`
  - Routes on `pickupType = SELF_DROP` → ack immediately, no processing
  - `originTileId` direct use if non-null; fallback to `GET /grid/tile-at` call if null (pending Q-M4-1 confirmation)
- **Idempotency guard:** before calling `assignPickup`, check `dispatch_queue` for existing row with `(shipment_id, task_type=PICKUP, operating_date=today)` → if exists, ack and skip (Kafka at-least-once; duplicate event must not create duplicate queue entry)
- 3 retries with exponential backoff (2s, 4s, 8s); park on `oneday.shipments.dlq` on exhaustion
- `@EmbeddedKafka` integration test: CREATED event → pickup assigned; SELF_DROP event → acked immediately; duplicate event → idempotent

---

### `M5 IMPL - PR #9 - ShipmentStateChangedConsumer, ShipmentCancelledConsumer, ExceptionsEventConsumer`

**Module:** `dispatch`

**What:**
- `ShipmentStateChangedConsumer`:
  - Routes on `state = HANDED_TO_DROP_VAN AND dropType = DA_DELIVERY` → `DispatchService.assignDelivery`
  - Routes on `dropType = HUB_COLLECT` → ack immediately
  - Idempotency: same guard as PR #8 (check for existing DELIVERY row before assigning)
- `ShipmentCancelledConsumer`:
  - On `CANCELLED` event: `DispatchService.cancelTask(shipmentId, PICKUP_OR_DELIVERY)`
  - If task is `IN_PROGRESS`: log WARN, emit ops alert — DA is en-route; M11 handles resolution
  - If task is `QUEUED`: set status=CANCELLED; remove from in-memory queue; resequence
- `ExceptionsEventConsumer` (subscribes to `oneday.exceptions.events`):
  - On `PICKUP_RESCHEDULED`: re-run `DispatchService.assignPickup` with rescheduled parameters
  - On `DELIVERY_RESCHEDULED`: re-run `DispatchService.assignDelivery`
- `DaEventProducer` (finalized here): wraps Kafka template; sets partition key to `shipment_id`; adds `schemaVersion = "1.0"` to all events
- All consumers: 3 retries + DLQ; `@JsonIgnoreProperties(ignoreUnknown = true)` on all event POJOs
- Integration tests with `@EmbeddedKafka`

---

## Phase 5 — DA-Facing API
> **DA app endpoints for GPS, task lifecycle, OTP, and delivery.**

---

### `M5 IMPL - PR #10 - DaDispatchController: GPS, en-route, van handoff, failure`

**Module:** `dispatch`

**What:**
- `DaDispatchController`:
  - `POST /dispatch/da/{da_id}/gps` → `DaStatusService.updateGps`; returns 204; auth: DA role JWT (pending Q-M1-1 for exact role name)
  - `POST /dispatch/da/{da_id}/tasks/{task_id}/en-route` → mark task IN_PROGRESS; update `DaStatus` to IN_PROGRESS; return task details + ETA
  - `POST /dispatch/da/{da_id}/tasks/{task_id}/van-handoff` → mark task COMPLETED; update `DaCronAssignment`; publish `VAN_HANDOFF_COMPLETED`; retry deferred orders; return next task
  - `POST /dispatch/da/{da_id}/tasks/{task_id}/failed` → mark task FAILED; publish `PICKUP_FAILED` or `DROP_FAILED` based on `task_type`
  - `POST /dispatch/da/{da_id}/tasks/{task_id}/drop-collected` → mark task IN_PROGRESS for delivery; publish `DROP_COLLECTED`
  - `POST /dispatch/da/{da_id}/tasks/{task_id}/drop-completed` → mark COMPLETED; publish `DROP_COMPLETED`; if COD, also publish `COD_COLLECTED`
- `@PreAuthorize` annotations using DA role constant (pending Q-M1-1)
- Request DTOs with `@Validated` input validation
- MockMvc integration tests for all endpoints (happy path + 404 on invalid task_id + 409 on wrong task status)

---

### `M5 IMPL - PR #11 - OtpVerificationService: M4 internal HTTP calls`

**Module:** `dispatch`

**Dependency:** Requires Q-M1-4 (internal service token mechanism) and Q-M4-3 (`{ref}` vs `{id}` on OTP endpoint) answered.

**What:**
- `OtpVerificationService` interface:
  - `verifyOtp(taskId, otp): OtpVerifyResult`
  - `resendOtp(taskId): ResendResult`
- `OtpVerificationServiceImpl`:
  - Calls `POST /internal/v1/shipments/{ref}/pickup-otp/verify` via `RestClient` (Spring 6 / Spring Boot 3.2 preferred over `RestTemplate`)
  - Internal service token auth header (implementation depends on Q-M1-4 answer — configure as `@ConfigurationProperties`-bound secret or JWT)
  - On 200: task already at PICKED_UP (M4 set it); M5 publishes `PICKUP_COMPLETED` (for M10 SLA leg)
  - On 422: return error code to controller → DA app shows error
  - On 503: Resilience4j retry (3 attempts, 2s/4s/8s); if still failing → emit `PICKUP_FAILED` with reason `OTP_SERVICE_UNAVAILABLE`
- `DaDispatchController` additions:
  - `POST /dispatch/da/{da_id}/tasks/{task_id}/verify-otp` → `OtpVerificationService.verifyOtp`
  - `POST /dispatch/da/{da_id}/tasks/{task_id}/resend-otp` → `OtpVerificationService.resendOtp`
- Resilience4j circuit breaker on M4 OTP HTTP calls — configured alongside call, not bolted on later
- WireMock integration tests: OTP correct path; OTP invalid (M4 returns 422); M4 503 → retry → eventual success; M4 503 × 3 → PICKUP_FAILED emitted

---

## Phase 6 — Station Manager View & Cross-Territory
> **Internal tooling and the cross-territory dispatch path.**

---

### `M5 IMPL - PR #12 - StationDispatchController and cross-territory dispatch`

**Module:** `dispatch`

**What:**
- `StationDispatchController`:
  - `GET /dispatch/tiles/{tile_id}/queue?date=YYYY-MM-DD` — returns full `TileQueueResponse` (DA statuses + queue depths + cron slack + deferred count)
  - Auth: `STATION_MANAGER` or `ADMIN` role (pending Q-M1-5 for exact role names)
  - Reads from in-memory `DaQueue` and `DaLiveStatus` maps; falls back to DB if tile is not currently loaded (historical date query)
- `TileQueueResponse` DTO
- Cross-territory dispatch implementation in `DispatchServiceImpl`:
  - Extract cross-territory search into its own method `findCrossTerritoryDa(tile, shipment)`
  - Call `GET /grid/tiles/{tile_id}/load-score` (M3 REST) to verify `adjustedLoadScore >= 1.5`
  - Iterate road-adjacent tiles (from M3's tile adjacency — pending Q-M3-2 for adjacency data availability)
  - Tag `cross_territory = true` and `home_tile_id` in `DispatchQueue` row
- MockMvc tests for station manager endpoint (auth checks; empty tile; historical date)
- Unit tests for cross-territory: only fires when both conditions met; adjacent DA selected by minimum cheapest-insertion cost

---

## Phase 7 — Scheduled Jobs & Kafka Publisher
> **Tile queue depth publishing and deferred retry loop.**

---

### `M5 IMPL - PR #13 - TileQueueDepthPublisher and DeferredRetryJob`

**Module:** `dispatch`

**What:**
- `TileQueueDepthPublisher` (`@Scheduled` every 5 minutes during shift hours):
  - Reads in-memory `DaQueue` maps for all active DAs in each city
  - Aggregates `unservedOrders` and `inProgressOrders` per tile
  - Publishes full-city snapshot to `oneday.dispatch.tile_queue_depth`
  - Payload per design doc §16.3
  - Not published outside shift hours (guard: `now < shiftStartTime || now > shiftEndTime`)
- `DeferredRetryJob` (`@Scheduled` every 5 minutes during shift hours):
  - Queries `deferred_dispatch` partial index for `status=PENDING AND retry_after <= now()`
  - For each row: call `DispatchService.assignPickup` or `assignDelivery`
  - On success: update row to `status=ASSIGNED, assigned_at=now()`
  - On still-infeasible: leave PENDING; update `retry_after` (back-off: +5 min each attempt up to max 30 min before shift end)
  - On `retry_after` reaching shift-end threshold: set `status=ESCALATED`; publish `TASK_DEFERRED_SHIFT_ENDED` → M11
- Unit tests: publisher skips outside shift hours; deferred retry escalates after max attempts; retry succeeds and row is updated to ASSIGNED

---

## Phase 8 — Resilience & Observability

---

### `M5 IMPL - PR #14 - Resilience hardening: OSRM circuit breaker, M3 fallbacks, DLQ tooling`

**Module:** `dispatch`

**What:**
- Resilience4j circuit breaker on `OsrmClient`: 5 failures → open → fast-path Haversine with `ROAD_FACTOR × 1.2` safety buffer; half-open after 30s
- Resilience4j circuit breaker on M3 load-score REST call (used in cross-territory check): 3 failures → skip cross-territory assignment, defer normally
- Resilience4j circuit breaker on M3 `tile-at` REST call (GPS-to-tile resolution): open → use last known tile from DA in-memory status
- All circuit breaker thresholds in `DispatchProperties` (not hardcoded)
- DLQ re-drive: `POST /internal/v1/dispatch/dlq/{messageId}/replay` operator endpoint (mirrors M4 PR #20)
- Chaos test: verify assignment completes in < 300ms when OSRM circuit is open (falls back to Haversine)

---

### `M5 IMPL - PR #15 - Micrometer metrics and structured logging`

**Module:** `dispatch`

**What:**
- Micrometer metrics:
  - `m5.assignment.duration` histogram (tagged by `task_type`, `outcome` [ASSIGNED/CROSS_TERRITORY/DEFERRED], `city_id`)
  - `m5.queue.depth` gauge per DA (polled from in-memory map)
  - `m5.cron.slack_seconds` histogram at assignment time (tagged by `city_id`)
  - `m5.deferred.count` gauge per defer reason (tagged by `city_id`, `defer_reason`)
  - `m5.otp.verify.duration` histogram (tagged by `outcome`)
  - `m5.absent.da.count` gauge per city
- Structured MDC logging: every log line during assignment includes `shipment_id`, `da_id`, `tile_id`, `city_id`
- `ERROR` log + metric when `originTileId` is null in `ShipmentCreatedEvent` (signals M4/M3 data gap)
- `ERROR` log + metric when cron assignment is missing for a DA at shift load (M6 plan not yet published)
- Custom `HealthIndicator`: in-memory queue map non-null; DB connectivity; OSRM reachable

---

## PR Dependency Graph

```
#1 (DaEventBase + DaEventType additions + topic constant in common)
 │
 ├──► [M10 can implement PICKUP_COMPLETED / VAN_HANDOFF_COMPLETED consumers]  ← parallel, other team
 ├──► [M11 can implement TASK_DEFERRED_SHIFT_ENDED consumer]                  ← parallel, other team
 │
 └─► #2 (Flyway migrations)
      └─► #3 (Entities + repositories)
           └─► #4 (DaStatusService + GPS + ShiftLoadJob)
                └─► #5 (AbsentDaDetectionJob + ShiftEndJob)
                     └─► #6 (CronFeasibilityService)
                          └─► #7 (DispatchServiceImpl + deferred dispatch)
                               ├─► #8 (ShipmentCreatedConsumer)              ← parallel
                               └─► #9 (StateChangedConsumer + Cancelled + Exceptions)
                                    └─► #10 (DaDispatchController: GPS + lifecycle)
                                         └─► #11 (OtpVerificationService + OTP endpoints)
                                              └─► #12 (StationManager + cross-territory)
                                                   └─► #13 (TileQueueDepth + DeferredRetryJob)
                                                        └─► #14 (Resilience hardening)
                                                             └─► #15 (Observability)
```

---

## What Other Teams Unlock at Each Phase

| After PR | Other teams can... |
|---|---|
| #1 | M10 implements SLA leg 1 consumers; M11 implements deferred task reschedule consumer |
| #7 | M6 team can verify that `cron.scheduled` events correctly populate `da_cron_assignment` via integration test |
| #8 | M4 team can verify end-to-end: `BOOKED → PICKUP_ASSIGNED` transition fires correctly when M5's consumer processes `CREATED` event |
| #11 | M4 team can verify `PICKUP_ASSIGNED → PICKED_UP` transition fires on OTP verify; M10 sees `PICKUP_COMPLETED` SLA event |
| #13 | M3 team can verify that `TileQueueDepthConsumer` receives correct payloads and updates in-memory load scores |

---

## Unresolved Dependencies (Block Specific PRs)

| Blocker | Blocks | Question ref |
|---------|--------|-------------|
| M1: DA JWT role name | PR #10 `@PreAuthorize` | Q-M1-1 |
| M1: Station manager JWT city scope | PR #12 `@PreAuthorize` | Q-M1-3 |
| M1: Internal service token mechanism | PR #11 `OtpVerificationServiceImpl` | Q-M1-4 |
| M3: REST controllers implemented? | PR #4 `ShiftLoadJob` (REST call), PR #8 fallback | Q-M3-1 |
| M3: `nDasOnTile` in assignments response | PR #4 `ShiftLoadJob` DA selection for shared tiles | Q-M3-2 |
| M3: `TileDemandSnapshot` access (REST vs direct repo) | PR #4 `ShiftLoadJob` service time load | Q-M3-5 |
| M4: `originTileId` always non-null? | PR #8 `ShipmentCreatedConsumer` | Q-M4-1 |
| M4: Delivery coords in `ShipmentStateChangedEvent`? | PR #9 `ShipmentStateChangedConsumer` | Q-M4-2 |
| M4: OTP endpoint uses `{ref}` or `{id}`? | PR #11 `OtpVerificationServiceImpl` | Q-M4-3 |
| M4: Delivery verification OD-8 (OTP vs QR) | PR #10 `drop-completed` endpoint shape | Q-M4-5 |
| M6: `cron.scheduled` event type defined? | PR #4 `ShiftLoadJob` (reads cron schedule) | Q-M4-9 |

---

## Testing Strategy

| Layer | Tool | When |
|---|---|---|
| Unit | JUnit 5 + Mockito | Every PR |
| Repository | `@DataJpaTest` + Testcontainers (PostgreSQL) | PR #3 onwards |
| Assignment engine | Parameterised JUnit5 (queue sizes 0–10, all feasibility branches) | PR #6 and #7 |
| Concurrency | `CountDownLatch` multi-thread test for per-DA lock correctness | PR #7 |
| Kafka consumers | `@EmbeddedKafka` | PR #8 onwards |
| M4 OTP HTTP | WireMock | PR #11 |
| M3 REST calls | WireMock | PR #4, #6, #12 |
| API | `@SpringBootTest` + MockMvc | PR #10 onwards |
| Resilience | Resilience4j test utilities + WireMock fault injection | PR #14 |
