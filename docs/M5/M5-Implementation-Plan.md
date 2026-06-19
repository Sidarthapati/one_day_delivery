# M5 — Implementation Plan

| Field | Value |
|-------|-------|
| Module | M5 — dispatch |
| Plan version | 3.1 (hyperlinked) |
| Based on | v2.0 + M5-REVIEW.md findings |
| Total PRs | 15 across 8 phases |

---

## How to use this plan

Each phase tells you:
1. **Read first** — linked directly to the exact section. Click, read, click back.
2. **What to build** — concrete implementation steps in dependency order.
3. **Verify** — the check that confirms this phase is done before moving on.

Work top-to-bottom within each phase. Do not start a phase until the previous one's verify step passes.

---

## ⚠️ Current-reality addendum (read before any phase)

This plan was written against an earlier codebase. Several things have since changed in `main`.
These global corrections **override** the per-phase text wherever they conflict:

1. **Messaging is RabbitMQ, not Kafka.** `KafkaTopics.java` → **`EventStreams.java`** (constants =
   RabbitMQ **topic exchanges**). Everywhere this plan says:
   - *"Kafka topic / KafkaTopics"* → an `EventStreams` constant (a RabbitMQ exchange).
   - *"producer / KafkaTemplate"* → inject **`common.kafka.EventPublisher`** and call
     `publish(EventStreams.X, event)`; the event is a record implementing **`DomainEvent`** whose
     `eventTypeName()` (the enum value) becomes the **routing key**.
   - *"`@KafkaListener` / consumer group"* → **`@RabbitListener(queues=…)`** plus a
     `DispatchMessagingTopology` `@Configuration` declaring the queue + binding (copy the pattern in
     `grid.events.GridMessagingTopology` using `RabbitStreamSupport.consumer(queue, exchange)`).
   - *"`@EmbeddedKafka`"* → Testcontainers **RabbitMQ** (or topology/unit tests).
   - *"partition key"* → routing key (the `DomainEvent.partitionKey()` hook still exists for stream
     ordering; it is not a Kafka partition).
   See `docs/EVENT-BUS-ARCHITECTURE.md`.

2. **The M6 cron contract is implemented — Q-M6-1 is RESOLVED.** M6 publishes
   **`common.kafka.events.cron.DaCronScheduledEvent`** `(cityId UUID, validDate, daId, cronVertexId,
   meetingLat, meetingLon, meetingTimes List<LocalTime>, vanId, routePlanId)` on
   `EventStreams.CRON_EVENTS` (routing key `DA_CRON_SCHEDULED`), persists the `da_cron_schedule`
   table, **and** serves `GET /routing/cron/da/{daId}?date=` + `/next?at=`. `ShiftLoadJob` (PR #4)
   can read whichever it prefers. **Note `meetingTimes` is a `List`** — M5 must store the list and
   pick the next meeting, not a single `scheduledMeetingTime` (M6-D-008).

3. **`DaEventType` and `DaEvent` already exist in `common`** — do not re-create them in PR #1. The
   enum already holds the 8 M4-consumed values; PR #1's job is only to **add the 5 internal values**.
   M4's minimal consumer record is `common.kafka.events.DaEvent(shipmentId, eventType)`; M5's
   producer may use a richer record (M4 ignores unknown fields). The abstract `DaEventBase` in PR #1
   is **superseded** — define M5's producer payload as a `DomainEvent` record instead.

4. **M3's `TileQueueDepthConsumer` is already built — Q-M3-4 is RESOLVED.** It `@RabbitListener`s on
   `EventStreams.TILE_QUEUE_DEPTH = "orders.tile_queue_depth"` and reads
   `TileQueueDepthEvent(cityId, tileId, unservedOrders, bookedOrders, date)`. **Open item:** the code
   attributes the *producer* to M4, while this plan assumes M5 (PR #13). Cleanest path: M5 publishes
   that same `TileQueueDepthEvent` to the existing `TILE_QUEUE_DEPTH` exchange (do **not** add a new
   `dispatch.*` constant) — but **confirm M4-vs-M5 producer ownership** with the M3/M4 owners first.

5. **M3 REST paths are `/api/grid/{cityCode}/…`**, not `/grid/…`:
   `GET /api/grid/{cityCode}/assignments?date=`, `/tiles/{hexId}/load-score`, `/serviceability`,
   `/tile-at`, plus top-level `GET /api/grid/serviceable-at?lat=&lon=`. **Better in-process:** M3
   implements `common.port.ServiceabilityPort` — prefer it over HTTP for tile resolution.
   (M3 is hexes/H3; "tile" in this plan ≡ "hex" in M3. Active solver is `BalancedBfsAssignmentServiceImpl`.)

6. **The one genuinely-unmet dependency:** `OsrmRoutingPort` (point-to-point road duration) still
   **does not exist** in `com.oneday.grid.service` — only `OsrmMatrixService` (adjacency matrix) and
   an internal `grid.service.osrm.OsrmClient`. PR #6 still needs M3 to expose a public routing port,
   or M5 must call OSRM `/route` over HTTP itself. This is the real Phase-3 blocker.

---

## 🏃 2-Day Sprint Plan

A compressed schedule to build M5 end-to-end in two focused days. **Assumes:** solo dev, ~8 focused
hours/day; "PR" = a self-reviewed commit unit (not a team review cycle); the three open dependencies
are **stubbed**, not blocked-on. Phase order is the strict dependency chain (see graph at the bottom).

### Pre-flight (≈30 min, before Day 1) — lock the 3 blockers
- **OSRM** (`OsrmRoutingPort` absent) → M5 calls OSRM `/route` over HTTP directly. *(Addendum §6.)*
- **OD-8 delivery verify** → choose **QR** (no extra M4 call; simpler than OTP). Decide, move on.
- **tile_queue_depth ownership** → publish to the existing `EventStreams.TILE_QUEUE_DEPTH` exchange
  (M3's consumer is already built). *(Addendum §4.)*

### Day 1 — Foundation + the algorithmic core (Phases 0–3a)

| Block | PRs | Work | KT read first |
|-------|-----|------|---------------|
| 0.5h | PR1 | `DaEventType` +5 values; M5 producer payload `DomainEvent` record | [§1b Messaging](M3-M6-KT-AND-HANDS-ON.md#kt-messaging) |
| 2.0h | PR2–3 | Flyway migrations + JPA entities + repos + `@DataJpaTest` | [§1 H3](M3-M6-KT-AND-HANDS-ON.md#kt-h3), [§3.5 cron](M3-M6-KT-AND-HANDS-ON.md#kt-m6-events) |
| 2.5h | PR4–5 | `DispatchProperties`, `DaStatusService` (in-memory maps), `ShiftLoadJob`, Cron/Absent/ShiftEnd jobs | [§2.6 M3 APIs](M3-M6-KT-AND-HANDS-ON.md#kt-m3-apis), [§2.2 demand](M3-M6-KT-AND-HANDS-ON.md#kt-demand), [§3.5 events](M3-M6-KT-AND-HANDS-ON.md#kt-m6-events) |
| 2.5h | PR6 | **`CronFeasibilityService` + exhaustive unit tests** (cheapest-insertion; the `×3600` fix) | [§3.1 plan-time](M3-M6-KT-AND-HANDS-ON.md#kt-m6-plan), [§3.5 events](M3-M6-KT-AND-HANDS-ON.md#kt-m6-events) |

**EOD1 = data layer + shift infra + feasibility engine, all unit-green.** This is the risky day —
protect the PR6 block.

### Day 2 — Wiring, API, jobs, hardening (Phases 3b–8)

| Block | PRs | Work | KT read first |
|-------|-----|------|---------------|
| 2.0h | PR7 | `DispatchServiceImpl` + per-DA lock + cross-territory + deferred retry + tests | [§7.2 needs-from-M6](M3-M6-KT-AND-HANDS-ON.md#kt-needs-m6) |
| 2.0h | PR8–9 | RabbitMQ consumers + `DispatchMessagingTopology` + Testcontainers tests | [§1b](M3-M6-KT-AND-HANDS-ON.md#kt-messaging), [§2.6](M3-M6-KT-AND-HANDS-ON.md#kt-m3-apis) |
| 2.0h | PR10–11 | `DaDispatchController` + OTP/QR service | [§3.2 M6 run-time](M3-M6-KT-AND-HANDS-ON.md#kt-m6-runtime) |
| 1.0h | PR12–13 | Station view + `TileQueueDepthPublisher` + `DeferredRetryJob` | [§7.1](M3-M6-KT-AND-HANDS-ON.md#kt-needs-m3), [§7.3](M3-M6-KT-AND-HANDS-ON.md#kt-gives) |
| 1.0h | PR14–15 | **Essentials only**: OSRM + M3 circuit breakers, key metrics, health indicator | — |

**EOD2 = bootable M5: shift load → assign → cron-gate → handoff → events out.**

### Risk & cut-list
- **Phase 3 (PR6/PR7) is the slip point** — it's the core; expect it to run long.
- **If behind, cut in this order:** ① Phase 8 polish (→ minimal breakers + 2-3 metrics) → ② Phase 6
  station view (read-only, safe to defer) → ③ cross-territory dispatch in PR7 (plain
  defer-on-infeasible is fine for v1).
- **Non-negotiable:** Phases 1–4 — without data + feasibility + consumers there is no demoable M5.

---

## Phase 0 — Shared Event Contracts (RabbitMQ) (PR #1)

> 📎 **Prior context (M3/M6):** the event bus is RabbitMQ, not Kafka — read
> [KT §1b · Messaging](M3-M6-KT-AND-HANDS-ON.md#kt-messaging) first, then ↩ back here.

**Module:** `common`

### Read first

| Doc | Sections | Why |
|-----|----------|-----|
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§1 What M5 Does](M5-DISPATCH-DESIGN.md#1-what-m5-does) · [§16.4 DaEventType additions](M5-DISPATCH-DESIGN.md#164-daeventtype-additions-needed-in-common) | Understand what M5 is and what event types need to exist globally |
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§16.3 tile\_queue\_depth topic](M5-DISPATCH-DESIGN.md#163-dispatchtile_queue_depth-m5--m3) | The one topic constant you're adding |

**~10 minutes of reading.**

---

<a id="phase-0-build"></a>

### What to build

> See the addendum: `DaEventType`, `DaEvent`, and `EventStreams` already exist. PR #1 is now small.

1. **DA event payload — do NOT create `DaEventBase`** (superseded). M4 already consumes the minimal
   `common.kafka.events.DaEvent(shipmentId, eventType)`. Define M5's *producer-side* payload as a
   record implementing **`DomainEvent`** (exposing `eventTypeName()` → routing key and
   `partitionKey()`); add the fields M5 needs (`daId`, location, timestamps, `cityId` as a UUID per
   the existing events). M4 ignores unknown fields, so producer additions never break it.

2. In `common/src/main/java/com/oneday/common/kafka/enums/DaEventType` (**it already exists** with
   the 8 M4-consumed values), **add the 5 internal values**:
   - `QUEUE_REORDERED`
   - `DA_ABSENT`
   - `CRON_MISSED`
   - `COD_COLLECTED`
   - `TASK_DEFERRED_SHIFT_ENDED`

3. **Tile queue-depth — do NOT add a new `dispatch.*` constant.** M3 already consumes
   `EventStreams.TILE_QUEUE_DEPTH` (`"orders.tile_queue_depth"`). Plan to publish the existing
   `TileQueueDepthEvent` shape to that exchange (PR #13) — **pending the M4-vs-M5 ownership
   confirmation in the addendum.** No `EventStreams` change in PR #1 if ownership lands on the
   existing exchange.

### Verify

```bash
mvn clean install -pl common
```
Compile-only — no business logic to test. Confirm the new `DaEventType` values compile and M5's
producer payload implements `DomainEvent`.

---

## Phase 1 — Database & Domain Layer (PRs #2–3)

> 📎 **Prior context (M3/M6):** "tile" in M5 ≡ "hex" in M3 —
> [KT §1 · H3](M3-M6-KT-AND-HANDS-ON.md#kt-h3); and `da_cron_assignment` is fed by M6's cron event —
> [KT §3.5 · M6 events](M3-M6-KT-AND-HANDS-ON.md#kt-m6-events). ↩ back here after.

**Module:** `dispatch`

### Read first

| Doc | Sections | Why |
|-----|----------|-----|
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§14.1 dispatch\_queue](M5-DISPATCH-DESIGN.md#141-dispatch_queue) · [§14.2 da\_cron\_assignment](M5-DISPATCH-DESIGN.md#142-da_cron_assignment) · [§14.3 da\_status](M5-DISPATCH-DESIGN.md#143-da_status) · [§14.4 deferred\_dispatch](M5-DISPATCH-DESIGN.md#144-deferred_dispatch) · [§14.5 da\_assignment\_audit](M5-DISPATCH-DESIGN.md#145-da_assignment_audit) | Every table you're about to create — columns, indexes, constraints |
| [M5-ER-DIAGRAM.md](M5-ER-DIAGRAM.md) | [Whole doc](M5-ER-DIAGRAM.md) | Visual map of how the tables relate |
| [M5-DA-STATUS-MACHINE.md](M5-DA-STATUS-MACHINE.md) | [Part 2 — Task Status State Machine](M5-DA-STATUS-MACHINE.md#part-2--task-status-state-machine-dispatchqueuestatus) | The `TaskStatus` enum values and their legal transitions |

**~20 minutes of reading.**

---

<a id="phase-1-pr2-build"></a>

### What to build — PR #2 (Flyway migrations, SQL only)

Create `dispatch/src/main/resources/db/migration/dispatch/`. Key mandatory details (bugs fixed from the original design):

- Column names must be `task_lat` / `task_lon` — **not** `pickup_lat` / `pickup_lon` (those names are wrong for delivery tasks)
- Unique index on `dispatch_queue` must be **partial** — a full unique constraint would block re-assignment after a FAILED attempt:
  ```sql
  CREATE UNIQUE INDEX idx_dispatch_queue_active_unique
      ON dispatch_queue (da_id, shipment_id, task_type, operating_date)
      WHERE status NOT IN ('FAILED', 'CANCELLED');
  ```
- Add index on `da_status` for the city/shift query hot path:
  ```sql
  CREATE INDEX idx_da_status_city_shift ON da_status (city_id, shift_date, status);
  ```
- Add `updated_at` trigger on `da_status` (the only fully mutable table):
  ```sql
  CREATE OR REPLACE FUNCTION set_updated_at()
  RETURNS TRIGGER AS $$
  BEGIN NEW.updated_at = now(); RETURN NEW; END;
  $$ LANGUAGE plpgsql;

  CREATE TRIGGER da_status_updated_at
  BEFORE UPDATE ON da_status
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
  ```
- All status/type columns: use `VARCHAR`, not PostgreSQL `ENUM` (values are still being finalised)
- `dispatch_queue` and `da_assignment_audit`: no `ON DELETE CASCADE` or `ON UPDATE CASCADE` (append-only tables)

Tables to create: `dispatch_queue`, `da_cron_assignment`, `da_status`, `deferred_dispatch`, `da_assignment_audit`

---

<a id="phase-1-pr3-build"></a>

### What to build — PR #3 (JPA entities + repositories)

1. **Local enums** (in `dispatch` module, not `common`):
   - `TaskType`: `PICKUP`, `DELIVERY`
   - `TaskStatus`: `QUEUED`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `DEFERRED`, `CANCELLED`
   - `DaStatusEnum`: `OFFLINE`, `IDLE`, `IN_PROGRESS`, `CRON_LOCKED`, `AT_CRON`, `ABSENT`
   - `DeferReason`: `NO_DA_AVAILABLE`, `CRON_INFEASIBLE`, `CRON_LOCKED`, `DA_ABSENT`, `SHIFT_ENDED`
   - `AssignmentDecision`: `ASSIGNED`, `CROSS_TERRITORY_ASSIGNED`, `DEFERRED_NO_DA`, `DEFERRED_CRON`, `DEFERRED_FROZEN`
   - `CronAssignmentStatus`: `SCHEDULED`, `COMPLETED`, `MISSED`, `CANCELLED`

2. **JPA entities** (all extend `BaseEntity` or `MutableBaseEntity` from `common`):
   - `DispatchQueue` — append-only pattern; only `status`, `started_at`, `completed_at`, `expected_eta` are mutable
   - `DaCronAssignment` — status and completion fields mutable
   - `DaStatus` — extends `MutableBaseEntity`; fully mutable; GPS + status updated often
   - `DeferredDispatch` — `status` and `retry_after` mutable
   - `DaAssignmentAudit` — fully append-only; no setters beyond constructor

3. **In-memory value objects** (plain Java, not JPA — never persisted directly):
   - `DispatchTask` — `(daId, shipmentId, taskLat, taskLon, queuePosition, status, expectedEta)`
   - `DaQueue` — holds `List<DispatchTask>` + cron schedule reference
   - `DaLiveStatus` — holds latest GPS coords, last heartbeat timestamp, current DA state

4. **Spring Data repositories** with these custom queries:
   - `DispatchQueueRepository`:
     - `findByDaIdAndOperatingDateOrderByQueuePosition`
     - `findByDaIdAndOperatingDateAndStatusIn`
     - `findActiveByShipmentIdAndTaskType` — uses the partial unique index
   - `DaCronAssignmentRepository`:
     - `findByDaIdAndOperatingDate`
     - `findByOperatingDateAndCityId`
   - `DaStatusRepository`:
     - `findByDaId`
     - `findByCityIdAndShiftDateAndStatusIn`
   - `DeferredDispatchRepository`:
     - `findPendingForRetry(cityId, now)` — targets the partial index `WHERE status='PENDING'`
   - `DaAssignmentAuditRepository`:
     - `findByShipmentId`

5. **Tests:** `@DataJpaTest` + Testcontainers PostgreSQL for all custom queries. Include a test that inserts a FAILED row then verifies a new active row for the same `(da_id, shipment_id, task_type, operating_date)` is accepted by the partial unique index.

### Verify

```bash
mvn clean install -pl dispatch
```
Also boot the app to let `ddl-auto=validate` confirm schema matches entities:
```bash
mvn spring-boot:run -pl app
```

---

## Phase 2 — Shift Infrastructure (PRs #4–5)

> 📎 **Prior context (M3/M6) — `ShiftLoadJob` consumes all three:** the DA→hex territory map
> ([KT §2.6 · M3 APIs](M3-M6-KT-AND-HANDS-ON.md#kt-m3-apis)), per-hex `service_time_min`
> ([KT §2.2 · demand-in-minutes](M3-M6-KT-AND-HANDS-ON.md#kt-demand)), and the per-DA cron schedule
> ([KT §3.5 · M6 events](M3-M6-KT-AND-HANDS-ON.md#kt-m6-events)). Read those, then ↩ back here.

**Module:** `dispatch`

### Read first

| Doc | Sections | Why |
|-----|----------|-----|
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§12.1 Shift structure](M5-DISPATCH-DESIGN.md#121-shift-structure) · [§12.2 DA status states](M5-DISPATCH-DESIGN.md#122-da-status-states) · [§12.3 GPS heartbeat](M5-DISPATCH-DESIGN.md#123-gps-heartbeat) · [§12.4 Absent DA detection](M5-DISPATCH-DESIGN.md#124-absent-da-detection) · [§12.5 Shift end](M5-DISPATCH-DESIGN.md#125-shift-end) | GPS heartbeat strategy, absent detection, shift end flow |
| [M5-DA-STATUS-MACHINE.md](M5-DA-STATUS-MACHINE.md) | [Part 1 — DA Status State Machine](M5-DA-STATUS-MACHINE.md#part-1--da-status-state-machine) · [Part 3 — DA Shift Timeline](M5-DA-STATUS-MACHINE.md#part-3--da-shift-timeline) | All valid DA status transitions + the AT\_CRON proximity trigger |
| [M5-SEQUENCES.md](M5-SEQUENCES.md) | [§8 GPS Heartbeat and Absent DA Detection](M5-SEQUENCES.md#8-gps-heartbeat-and-absent-da-detection) · [§9 Shift Load](M5-SEQUENCES.md#9-shift-load-start-of-day) | How GPS flush and shift load actually flow step by step |

**~20 minutes of reading.**

**Preconditions — get answers to these before coding PR #4:**
- Q-M3-1: ✅ **Resolved** — M3 controllers are live at `/api/grid/{cityCode}/…` (+ in-process
  `ServiceabilityPort`). Use the real paths (see addendum §5).
- Q-M3-5: How is `service_time_min` accessed — REST endpoint, public service method, or shared repo?
  (still open — decide with M3 owner)
- Q-M6-1: ✅ **Resolved** — `DaCronScheduledEvent` on `EventStreams.CRON_EVENTS` (+ `da_cron_schedule`
  table + `GET /routing/cron/da/{daId}`). `ShiftLoadJob` reads any of these; remember `meetingTimes`
  is a **list**.

---

<a id="phase-2-pr4-build"></a>

### What to build — PR #4 (DispatchProperties, DaStatusService, ShiftLoadJob)

1. **`DispatchProperties`** (`@ConfigurationProperties("dispatch")`):
   ```yaml
   dispatch:
     cron:
       freeze-minutes: 30
     da:
       absent-threshold-minutes: 15
     gps:
       heartbeat-interval-seconds: 30
       flush-interval-seconds: 120
     osrm:
       confirm-threshold-minutes: 20
     travel:
       road-factor: 1.4
       avg-speed-kmph: 25
     shift:
       load-offset-minutes: 15
   ```
   No hardcoded literals anywhere in M5 — every threshold comes from here.

2. **`DaStatusService` interface** + package-private `DaStatusServiceImpl`:

   Three `ConcurrentHashMap`s as the in-memory store:
   - `ConcurrentHashMap<UUID, DaLiveStatus>` — GPS + heartbeat state
   - `ConcurrentHashMap<UUID, DaQueue>` — ordered task queue per DA
   - `ConcurrentHashMap<UUID, ReentrantLock>` — one lock per DA (used in Phase 3)

   Methods:
   - `initShift(daId, cityId, shiftDate, shiftType, cronAssignment)` — populates all three maps; idempotent
   - `updateGps(daId, lat, lon, timestamp)` — updates `DaLiveStatus`; marks dirty; **if status is `CRON_LOCKED` and GPS is within 200m of `cron_vertex`, transition to `AT_CRON`**
   - `updateStatus(daId, newStatus)` — updates in-memory + DB synchronously; acquires per-DA lock
   - `flushDirtyStatuses()` — `@Scheduled` batch UPDATE every `flush-interval-seconds`; skips clean rows
   - `getStatus(daId)` — reads in-memory only, never DB on the hot path

3. **`ShiftLoadJob`** (`@Scheduled` at `shiftStartTime − loadOffsetMinutes`):
   1. Call `GET /api/grid/{cityCode}/assignments?date={}` (or the in-process grid service) → build
      `Map<UUID, List<UUID>>` (daId → hexIds)
   2. Get today's cron schedule from M6 — consume `DaCronScheduledEvent` into `da_cron_assignment`,
      or read M6's `da_cron_schedule` table / `GET /routing/cron/da/{daId}`. Store the **list** of
      `meetingTimes`.
   3. Read `service_time_min` per hex (per Q-M3-5 answer)
   4. For each DA: call `initShift(...)`
   5. **Restart recovery:** for any `IN_PROGRESS` `DispatchQueue` row where `expected_eta < now()`, reset `expected_eta = now()` before loading into the in-memory queue

4. **Tests:**
   - GPS flush batches dirty rows; skips clean ones
   - `initShift` is idempotent (safe to call twice on restart)
   - Stale `expected_eta` is reset during restart recovery
   - WireMock stubs for M3 REST calls

---

<a id="phase-2-pr5-build"></a>

### What to build — PR #5 (CronMonitorJob, AbsentDaDetectionJob, ShiftEndJob)

1. **`CronMonitorJob`** (`@Scheduled` every 5 minutes during shift hours):
   - For each DA not in `{OFFLINE, CRON_LOCKED, AT_CRON, ABSENT}`: if `scheduledMeetingTime − now() ≤ cronFreezeMinutes` → `updateStatus(daId, CRON_LOCKED)`
   - The `AT_CRON` transition happens inside `updateGps` via the 200m proximity check — not here

2. **`AbsentDaDetectionJob`** (`@Scheduled` every 5 minutes during shift hours):
   - For each DA where `now − lastHeartbeat > absentThresholdMinutes` and `status ∉ {ABSENT, OFFLINE}`:
     - `updateStatus(daId, ABSENT)`
     - Publish `DaEvent(DA_ABSENT)` via `DaEventProducer`

3. **`ShiftEndJob`** (`@Scheduled` at `shift_end_time`):
   - For each DA with `QUEUED` tasks: set rows to `DEFERRED`; insert `DeferredDispatch(SHIFT_ENDED)`; publish `TASK_DEFERRED_SHIFT_ENDED` → M11
   - Set all DA statuses to `OFFLINE`
   - Call `flushDirtyStatuses()` immediately (don't wait for the 2-min timer)
   - Clear all three in-memory maps

4. **Tests:**
   - `CronMonitorJob` sets `CRON_LOCKED` only when within freeze window; doesn't re-lock already-`CRON_LOCKED` DAs
   - Absent detection skips `OFFLINE` and already-`ABSENT` DAs
   - Shift-end defers only `QUEUED` tasks, not `IN_PROGRESS` or `COMPLETED`
   - Shift-end is idempotent (safe to run twice if pod restarts at boundary)

### Verify

```bash
mvn clean install -pl dispatch
```

---

## Phase 3 — Core Assignment Engine (PRs #6–7)

> 📎 **Prior context (M3/M6) — cron-meeting feasibility depends on M6:** how the loop/meeting-time
> model works ([KT §3.1 · M6 plan-time](M3-M6-KT-AND-HANDS-ON.md#kt-m6-plan)), the meeting-times
> **list** contract ([KT §3.5 · M6 events](M3-M6-KT-AND-HANDS-ON.md#kt-m6-events)), `service_time_min`
> ([KT §2.2](M3-M6-KT-AND-HANDS-ON.md#kt-demand)), and what M5 needs from M6
> ([KT §7.2](M3-M6-KT-AND-HANDS-ON.md#kt-needs-m6)). ↩ back here after.

**The algorithmic heart of M5. Exhaustively unit-test before any messaging wiring touches it.**

### Read first

| Doc | Sections | Why |
|-----|----------|-----|
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§8 Cron-Meeting Feasibility](M5-DISPATCH-DESIGN.md#8-cron-meeting-feasibility-constraint) (all 5 subsections) | What feasibility means; IN\_PROGRESS handling; the freeze window |
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§9 Cheapest-Insertion Heuristic](M5-DISPATCH-DESIGN.md#9-cheapest-insertion-heuristic) (all 4 subsections) | The exact algorithm you're implementing |
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§10 Cross-Territory Dispatch](M5-DISPATCH-DESIGN.md#10-cross-territory-dispatch) (all 4 subsections) | The fallback when no feasible DA exists in the primary tile |
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§6.1 Pickup Assignment Happy Path](M5-DISPATCH-DESIGN.md#61-happy-path) | The end-to-end flow the assignment service must produce |
| [M5-SEQUENCES.md](M5-SEQUENCES.md) | [§1 Happy Path](M5-SEQUENCES.md#1-pickup-assignment--happy-path) · [§2 Borderline Cron / OSRM](M5-SEQUENCES.md#2-pickup-assignment--borderline-cron-osrm-confirmation) · [§3 Infeasible → Deferred → Retry](M5-SEQUENCES.md#3-pickup-assignment--cron-infeasible--deferred--retry) | Step-by-step sequences for all three assignment outcomes |

**~25 minutes of reading.**

**Precondition (CONFIRMED still unmet — the one real blocker):** `OsrmRoutingPort` does **not** exist
in `com.oneday.grid.service`. Grid only has `OsrmMatrixService` (adjacency matrix, not point-to-point)
and an internal `grid.service.osrm.OsrmClient` (can't be imported cross-module). Either M3 exposes a
public `OsrmRoutingPort.routeDurationSeconds(waypoints)` (thin wrap over `OsrmClient`), or M5 calls
OSRM `/route` over HTTP directly (as the M6 demo UI does). Raise with the M3 team now — longest-lead
item in this phase.

---

<a id="phase-3-pr6-build"></a>

### What to build — PR #6 (CronFeasibilityService)

1. **`CronFeasibilityService` interface:**
   ```java
   public interface CronFeasibilityService {
       FeasibilityResult checkFeasibility(
           UUID daId,
           List<DispatchTask> proposedQueue,
           LatLon newTaskLocation,
           LatLon cronVertex,
           Instant scheduledMeetingTime
       );
   }
   ```

2. **`FeasibilityResult` record:**
   ```java
   record FeasibilityResult(
       boolean feasible,
       int     bestInsertionIndex,
       long    cronSlackSeconds,
       long    extraTravelSeconds,
       boolean usedOsrm
   ) {}
   ```

3. **`CronFeasibilityServiceImpl`** (package-private):

   Travel time helper — note the `× 3600` (the original design had a bug where the formula returned hours, not seconds):
   ```java
   long travelSeconds(LatLon from, LatLon to) {
       double km = haversineKm(from, to);
       return Math.round((km * roadFactor / avgSpeedKmph) * 3600);
   }
   ```

   Cheapest-insertion loop:
   ```
   for k in 0..n:
     extraTravel = travel(P_k → P_new) + travel(P_new → P_{k+1}) − travel(P_k → P_{k+1})
     extraTime   = extraTravel + serviceTimeSeconds(tileOf(P_new))
     arrivalAtCron = T_current + existingTotalTime + extraTime
     if arrivalAtCron ≤ scheduledMeetingTime → record (k, extraTravel)
   best = min(extraTravel) among feasible candidates
   ```

   - If DA has an `IN_PROGRESS` task: `P_0 = inProgressTask.taskLatLon`, `T_current = inProgressTask.expectedEta`
   - OSRM slow-path: when `|arrivalAtCron − scheduledMeetingTime| < osrmConfirmThresholdSeconds`, call `osrmRoutingPort.routeDurationSeconds(waypoints)` to confirm
   - OSRM circuit-breaker fallback: if breaker is open, use `roadFactor × 1.2`; set `usedOsrm = false`
   - Wire `OsrmRoutingPort` from `grid` module — the **interface**, never the impl class

4. **Tests (parameterised JUnit 5 — every branch):**
   - Empty queue: single insertion; feasibility = GPS→task + service + task→cron ≤ slack
   - Single-item queue: two positions; minimum extra travel wins
   - All positions infeasible → `feasible = false`
   - Borderline: OSRM is called and its result overrides the fast path
   - OSRM circuit open: falls back to `1.2 × roadFactor`; `usedOsrm = false`
   - Formula sanity: 5 km at 25 km/h × 1.4 road factor must equal **1008 seconds** (not 0.28)
   - Concurrent: two inserts under per-DA lock produce correct sequential feasibility

---

<a id="phase-3-pr7-build"></a>

### What to build — PR #7 (DispatchServiceImpl)

1. **`DispatchService` interface:**
   ```java
   public interface DispatchService {
       AssignmentResult assignPickup(UUID shipmentId, String cityId,
                                     double lat, double lon,
                                     UUID originTileId, String paymentMode);
       AssignmentResult assignDelivery(UUID shipmentId, String cityId,
                                       double lat, double lon, UUID destTileId);
       void             cancelTask(UUID shipmentId, TaskType taskType);
       AssignmentResult reassignDeferred(UUID deferredId);
   }
   ```

2. **`DispatchServiceImpl`** (package-private) — `assignPickup` flow:
   1. Resolve tile (`originTileId` if non-null; else call M3 `tile-at`)
   2. Find active DAs for tile from in-memory map; pick smallest `queueDepth`
   3. No DA → write `DeferredDispatch(NO_DA_AVAILABLE)` → return `DEFERRED`
   4. DA is `CRON_LOCKED` → write `DeferredDispatch(CRON_LOCKED, retry_after = cronTime + 15 min)` → return `DEFERRED`
   5. **Acquire per-DA `ReentrantLock`**
   6. Call `CronFeasibilityService.checkFeasibility` with current queue state (under lock)
   7. If infeasible at all positions:
      - Check cross-territory: `loadScore ≥ 1.5` AND adjacent DA `adjustedLoadScore < 0.8`
      - If eligible: run feasibility against adjacent DA; if feasible → assign with `cross_territory = true`
      - Else → write `DeferredDispatch(CRON_INFEASIBLE)` → return `DEFERRED`
   8. Insert `DispatchQueue` row at `bestInsertionIndex`; bump `queue_position` of all later rows
   9. Update in-memory `DaQueue` (still under lock)
   10. Write `DaAssignmentAudit` row (every path — assigned, cross-territory, and all deferred paths)
   11. **Release lock**
   12. Return `AssignmentResult`

3. **Post-cron deferred retry** (the original design had no answer for what happens after the cron meeting):
   In `DeferredRetryService.reassign(deferred)`: if the DA's `da_cron_assignment.status = COMPLETED`, skip the cron constraint entirely and use hub-return as a soft constraint only.

4. **`cancelTask`:**
   - `QUEUED` → set `CANCELLED`; remove from in-memory queue; resequence remaining
   - `IN_PROGRESS` → log WARN + emit ops alert; return error (M11 handles operationally)

5. **Tests:**
   - Concurrent lock: two threads arrive simultaneously for the same DA; second sees queue with first order already inserted
   - `CRON_LOCKED` DA: deferred immediately without calling feasibility check
   - Deferred retry: row flips to `ASSIGNED` on success; stays `PENDING` if still infeasible
   - Cross-territory: only triggers when BOTH conditions are met; not when only one is
   - Post-cron retry: cron constraint skipped when `da_cron_assignment.status = COMPLETED`
   - `DaAssignmentAudit` row written for every decision path without exception
   - Cancel of `IN_PROGRESS` task returns error; status unchanged in DB

### Verify

```bash
mvn clean install -pl dispatch
```

---

## Phase 4 — Event Consumers (RabbitMQ) (PRs #8–9)

> Per the addendum: consumers are `@RabbitListener`s declared against queues bound in a
> `DispatchMessagingTopology` (`RabbitStreamSupport.consumer(queue, exchange)`); "consumer group" →
> a named queue; `oneday.shipments.dlq` → a RabbitMQ dead-letter queue; `@EmbeddedKafka` →
> Testcontainers RabbitMQ.

> 📎 **Prior context (M3/M6):** the RabbitMQ consumer/topology pattern
> ([KT §1b · Messaging](M3-M6-KT-AND-HANDS-ON.md#kt-messaging)) and how to resolve a pickup coord →
> hex ([KT §2.6 · M3 APIs](M3-M6-KT-AND-HANDS-ON.md#kt-m3-apis)). ↩ back here after.

**Module:** `dispatch`

### Read first

| Doc | Sections | Why |
|-----|----------|-----|
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§16.1 Events consumed by M5](M5-DISPATCH-DESIGN.md#161-events-consumed-by-m5) | What topics M5 subscribes to and what it does with each event type |
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§5 Fulfillment Path Routing](M5-DISPATCH-DESIGN.md#5-fulfillment-path-routing) | Why some events are acked immediately (SELF\_DROP, HUB\_COLLECT) |
| [M5-SEQUENCES.md](M5-SEQUENCES.md) | [§3 Infeasible → Deferred → Retry](M5-SEQUENCES.md#3-pickup-assignment--cron-infeasible--deferred--retry) | How the consumer hands off to assignment and handles the deferred case |

**~15 minutes of reading.**

**Preconditions:** Q-M4-1 (`originTileId` nullability in `ShipmentCreatedEvent`) and Q-M4-2 (are delivery coords present in `ShipmentStateChangedEvent`?) must be answered before writing these consumers.

---

<a id="phase-4-pr8-build"></a>

### What to build — PR #8 (ShipmentCreatedConsumer)

- Queue `m5.shipment-created` bound to `EventStreams.SHIPMENTS_EVENTS` (`oneday.shipments.events`) in `DispatchMessagingTopology`
- `eventType = CREATED AND pickupType = DA_PICKUP` → `DispatchService.assignPickup`
- `pickupType = SELF_DROP` → ack immediately, no processing
- **Idempotency guard:** before `assignPickup`, call `findActiveByShipmentIdAndTaskType(shipmentId, PICKUP)`. If an active row already exists, ack and skip. A FAILED row does not block re-assignment (partial unique index allows it).
- `originTileId` null handling (depends on Q-M4-1 answer): if null, resolve via `ServiceabilityPort` / `GET /api/grid/{cityCode}/tile-at`; log ERROR + increment metric for the data gap
- Error handling: 3 retries with exponential backoff (2s → 4s → 8s); park on `oneday.shipments.dlq`

**Tests (Testcontainers RabbitMQ):** `DA_PICKUP` event → pickup assigned; `SELF_DROP` → acked, no DB row; duplicate event → idempotent; FAILED row exists → re-assignment proceeds.

---

<a id="phase-4-pr9-build"></a>

### What to build — PR #9 (ShipmentStateChangedConsumer, ShipmentCancelledConsumer, ExceptionsEventConsumer)

1. `ShipmentStateChangedConsumer`:
   - `state = HANDED_TO_DROP_VAN AND dropType = DA_DELIVERY` → `assignDelivery`
   - `dropType = HUB_COLLECT` → ack immediately
   - Same idempotency guard as PR #8 for `DELIVERY` task type

2. `ShipmentCancelledConsumer`:
   - `CANCELLED` → `cancelTask(shipmentId, taskType)`
   - `IN_PROGRESS` task: log WARN; emit ops alert metric; ack (M11 resolves operationally)
   - `QUEUED` task: cancel + remove from queue + publish `QUEUE_REORDERED`

3. `ExceptionsEventConsumer` (topic `oneday.exceptions.events`):
   - `PICKUP_RESCHEDULED` → `assignPickup` with rescheduled coords
   - `DELIVERY_RESCHEDULED` → `assignDelivery` with rescheduled coords

4. **`DaEventProducer`** — publishes via `EventPublisher.publish(EventStreams.DA_EVENTS, event)`.
   Implement `DomainEvent.partitionKey()` on M5's event record (the stream-ordering key, not a Kafka
   partition):
   ```java
   String partitionKey = isShipmentScopedEvent(eventType)
       ? shipmentId.toString()
       : daId.toString();   // QUEUE_REORDERED, DA_ABSENT, CRON_MISSED → keyed by daId
   ```

**Tests (Testcontainers RabbitMQ):** delivery assignment; QUEUED vs IN_PROGRESS cancellation; reschedule re-runs assignment; `QUEUE_REORDERED` uses `daId` as its `partitionKey()`.

### Verify

```bash
mvn clean install -pl dispatch
```

---

## Phase 5 — DA-Facing API (PRs #10–11)

> 📎 **Prior context (M3/M6):** the van-handoff endpoint sits on M6's custody chain (deliver/collect
> at the meeting vertex) — read [KT §3.2 · M6 run-time](M3-M6-KT-AND-HANDS-ON.md#kt-m6-runtime)
> (designed, not yet built) before wiring `van-handoff`. ↩ back here after.

**Module:** `dispatch`

### Read first

| Doc | Sections | Why |
|-----|----------|-----|
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§15 API Surface](M5-DISPATCH-DESIGN.md#15-api-surface) (§15.1–§15.9) | Every endpoint the DA app calls — request/response shapes |
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§6.2 OTP pickup flow](M5-DISPATCH-DESIGN.md#62-otp-pickup-flow) · [§6.3 Van handoff](M5-DISPATCH-DESIGN.md#63-van-handoff) | The two complex flows behind the lifecycle endpoints |
| [M5-SEQUENCES.md](M5-SEQUENCES.md) | [§4 OTP Verification](M5-SEQUENCES.md#4-otp-verification-pickup-confirmation) · [§5 Van Handoff](M5-SEQUENCES.md#5-van-handoff-cron-meeting) | Step-by-step sequences to guide the implementation |

**~20 minutes of reading.**

**Preconditions:** Q-M1-1 (DA JWT role name), Q-M4-5 (OD-8: OTP vs QR for delivery confirmation), Q-M1-4 (internal service token mechanism), Q-M4-3 (OTP endpoint uses `{ref}` or `{id}`?).

---

<a id="phase-5-pr10-build"></a>

### What to build — PR #10 (DaDispatchController)

All DA endpoints use `@PreAuthorize` with DA identity check (the original design skipped this — any JWT would have passed):
```java
@PreAuthorize("hasRole('{DA_ROLE}') and authentication.name == #daId.toString()")
```

| Method + Path | Action | Event emitted |
|---------------|--------|---------------------|
| `POST /dispatch/da/{da_id}/gps` | `DaStatusService.updateGps`; 204 | — |
| `POST /dispatch/da/{da_id}/tasks/{task_id}/en-route` | Mark `IN_PROGRESS`; return task + ETA | — |
| `POST /dispatch/da/{da_id}/tasks/{task_id}/van-handoff` | Mark COMPLETED; update `DaCronAssignment`; trigger deferred retry | `VAN_HANDOFF_COMPLETED` |
| `POST /dispatch/da/{da_id}/tasks/{task_id}/failed` | Mark FAILED | `PICKUP_FAILED` or `DROP_FAILED` |
| `POST /dispatch/da/{da_id}/tasks/{task_id}/drop-collected` | Mark `IN_PROGRESS` for delivery | `DROP_COLLECTED` |
| `POST /dispatch/da/{da_id}/tasks/{task_id}/drop-completed` | Mark COMPLETED; if COD also emit `COD_COLLECTED` | `DROP_COMPLETED` |

`van-handoff` body (the original design accepted one scan; the event contract implies an array):
```json
{
  "parcel_scans": ["BLR-...", "BLR-..."],
  "van_id": "...",
  "timestamp": "..."
}
```
Validate each scan against a `COMPLETED` `DispatchQueue` row for this DA. Count mismatch → 422.

**Tests (MockMvc):** happy path per endpoint; 404 on invalid `task_id`; 409 on wrong task status; 403 when JWT `daId ≠ path daId`; `van-handoff` scan not in queue → 422.

---

<a id="phase-5-pr11-build"></a>

### What to build — PR #11 (OtpVerificationService + OTP endpoints)

1. **`OtpVerificationService` interface:**
   ```java
   public interface OtpVerificationService {
       OtpVerifyResult verifyOtp(UUID taskId, String otp);
       ResendResult    resendOtp(UUID taskId);
   }
   ```

2. **`OtpVerificationServiceImpl`** (package-private):
   - Look up `shipmentRef` from `DispatchQueue` by `taskId`
   - Call `POST /internal/v1/shipments/{ref}/pickup-otp/verify` via `RestClient` with internal service token
   - On 200: M4 has set `PICKED_UP`; M5 publishes `PICKUP_COMPLETED` (M10 SLA leg trigger)
   - On 422: return error code (`OTP_INVALID | OTP_EXPIRED | MAX_RETRIES_EXCEEDED`) → controller 422
   - On 503: Resilience4j retry (2s → 4s → 8s); if still failing → emit `PICKUP_FAILED(OTP_SERVICE_UNAVAILABLE)`

3. New endpoints on `DaDispatchController`:
   ```
   POST /dispatch/da/{da_id}/tasks/{task_id}/verify-otp   body: { otp }
   POST /dispatch/da/{da_id}/tasks/{task_id}/resend-otp   response: { resends_remaining }
   ```

**Tests (WireMock for M4 calls):** correct OTP; wrong OTP (M4 422); M4 503 once then success; M4 503 × 3 → `PICKUP_FAILED` emitted.

### Verify

```bash
mvn clean install -pl dispatch
```

---

## Phase 6 — Station Manager View (PR #12)

> 📎 **Prior context (M3/M6):** the load-score the view surfaces is M3's overload signal — see what
> M5 needs from M3 ([KT §7.1](M3-M6-KT-AND-HANDS-ON.md#kt-needs-m3)). ↩ back here after.

**Module:** `dispatch`

### Read first

| Doc | Sections | Why |
|-----|----------|-----|
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§15.9 Station manager: tile dispatch view](M5-DISPATCH-DESIGN.md#159-station-manager-tile-dispatch-view) | The single endpoint and its response shape |
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§10 Cross-Territory Dispatch](M5-DISPATCH-DESIGN.md#10-cross-territory-dispatch) | Cross-territory tags that appear in the station manager view |

**~10 minutes of reading.**

**Preconditions:** Q-M1-3 (station manager JWT city scope claim), Q-M1-5 (ADMIN role name).

---

<a id="phase-6-pr12-build"></a>

### What to build — PR #12

1. `StationDispatchController`:
   ```
   GET /dispatch/tiles/{tile_id}/queue?date=YYYY-MM-DD
   Auth: STATION_MANAGER (city-scoped) or ADMIN
   ```
   - Current date → read from in-memory `DaQueue` + `DaLiveStatus`
   - Historical date → read from DB only
   - City-scope: extract `cityId` from JWT (per Q-M1-3); verify `tile.cityId == jwtCityId`; skip check for ADMIN

2. `TileQueueResponse` DTO — match the shape in [§15.9](M5-DISPATCH-DESIGN.md#159-station-manager-tile-dispatch-view).

**Tests (MockMvc):** station manager sees only their city; 403 on another city; ADMIN sees any city; empty tile → empty `das` array; historical date uses DB path.

### Verify

```bash
mvn clean install -pl dispatch
```

---

## Phase 7 — Scheduled Jobs & Event Publisher (PR #13)

> 📎 **Prior context (M3/M6):** the queue-depth feed M5 publishes (and the M4-vs-M5 ownership
> question) — read what M5 gives the system
> ([KT §7.3](M3-M6-KT-AND-HANDS-ON.md#kt-gives)). ↩ back here after.

**Module:** `dispatch`

### Read first

| Doc | Sections | Why |
|-----|----------|-----|
| [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) | [§16.3 dispatch.tile\_queue\_depth payload](M5-DISPATCH-DESIGN.md#163-dispatchtile_queue_depth-m5--m3) | Exact JSON payload shape for the publisher |
| [M5-SEQUENCES.md](M5-SEQUENCES.md) | [§3 Deferred → Retry](M5-SEQUENCES.md#3-pickup-assignment--cron-infeasible--deferred--retry) (bottom half — from "RetryJob" onward) | How the retry job flows back into the assignment service |

**~10 minutes of reading.**

---

<a id="phase-7-pr13-build"></a>

### What to build — PR #13

1. **`TileQueueDepthPublisher`** (`@Scheduled` every 5 minutes):
   - Guard: no-op if outside shift hours
   - Aggregate `unservedOrders` (QUEUED tasks) and `inProgressOrders` (IN_PROGRESS tasks) per tile from in-memory maps
   - Publish a `TileQueueDepthEvent(cityId, tileId, unservedOrders, bookedOrders, date)` via
     `EventPublisher.publish(EventStreams.TILE_QUEUE_DEPTH, …)` so M3's existing `TileQueueDepthConsumer`
     picks it up — **pending the M4-vs-M5 producer-ownership confirmation** (addendum §4). Match the
     payload shape M3 already reads.

2. **`DeferredRetryJob`** (`@Scheduled` every 5 minutes):
   - Guard: no-op if outside shift hours
   - Query `DeferredDispatchRepository.findPendingForRetry(cityId, now)`
   - On assignment success → `status = ASSIGNED`
   - On still-infeasible → leave `PENDING`; bump `retry_after += 5 min` (max 3 retries)
   - After 3 retries OR `retry_after ≥ shiftEnd − 30 min` → `status = ESCALATED`; publish `TASK_DEFERRED_SHIFT_ENDED` → M11

**Tests:** publisher no-ops outside shift hours; publisher aggregates correctly across DAs sharing a tile; retry escalates after 3 failures; retry escalates when approaching shift end; successful retry flips row to ASSIGNED and does not retry again.

### Verify

```bash
mvn clean install -pl dispatch
```

---

## Phase 8 — Resilience + Observability (PRs #14–15)

**Module:** `dispatch`

### Read first

No new doc reading needed — all design is already covered by prior phases.

---

<a id="phase-8-pr14-build"></a>

### What to build — PR #14 (Circuit breakers + DLQ replay)

Resilience4j circuit breakers — every threshold in `DispatchProperties`, none hardcoded:

| Component | Opens after | Fallback |
|-----------|-------------|---------|
| `OsrmRoutingPort` | 5 failures | Haversine with `roadFactor × 1.2`; log WARN |
| M3 load-score REST (cross-territory check) | 3 failures | Skip cross-territory; defer normally |
| M3 `tile-at` REST | 3 failures | Use DA's last known tile from `DaLiveStatus`; log WARN |

Half-open probe: 30 seconds for all breakers.

DLQ re-drive endpoint:
```
POST /internal/v1/dispatch/dlq/{messageId}/replay
Auth: ADMIN
```

**Chaos tests:** assignment completes in < 300ms when OSRM circuit is open; cross-territory skips gracefully when M3 load-score circuit is open; no assignment lost when M3 `tile-at` is unavailable.

---

<a id="phase-8-pr15-build"></a>

### What to build — PR #15 (Metrics + structured logging)

Micrometer metrics:

| Metric | Type | Tags |
|--------|------|------|
| `m5.assignment.duration` | Histogram | `task_type`, `outcome` (ASSIGNED/CROSS_TERRITORY/DEFERRED), `city_id` |
| `m5.queue.depth` | Gauge per DA | `da_id`, `city_id` |
| `m5.cron.slack_seconds` | Histogram | `city_id` |
| `m5.deferred.count` | Gauge | `city_id`, `defer_reason` |
| `m5.otp.verify.duration` | Histogram | `outcome` (SUCCESS/INVALID/ERROR) |
| `m5.absent.da.count` | Gauge | `city_id` |
| `m5.assignment.osrm_confirmations` | Counter | `city_id`, `result` (CONFIRMED/OVERRIDDEN) |

MDC fields set for every log line during assignment: `shipment_id`, `da_id`, `tile_id`, `city_id`.

ERROR log lines (alert-worthy):
- `originTileId` null in `ShipmentCreatedEvent` — signals M4/M3 data gap
- Cron assignment missing for a DA at shift load — signals M6 plan not published
- `van-handoff` scan count mismatch vs completed tasks

Custom `HealthIndicator`: in-memory queue map non-null (shift loaded); DB connectivity; OSRM reachable.

### Verify

```bash
mvn clean install -pl dispatch
```

---

## PR Dependency Graph

```
#1  common: DaEventBase + DaEventType + topic constant
│
│   M10 and M11 can start their consumers here (parallel, other teams)
│
└─► #2  Flyway migrations
     └─► #3  JPA entities + repositories
          └─► #4  DispatchProperties + DaStatusService + ShiftLoadJob
               └─► #5  CronMonitorJob + AbsentDaDetectionJob + ShiftEndJob
                    └─► #6  CronFeasibilityService
                         └─► #7  DispatchServiceImpl + per-DA lock
                              ├─► #8  ShipmentCreatedConsumer        ← parallel with #9
                              └─► #9  StateChanged + Cancelled + Exceptions consumers
                                   └─► #10 DaDispatchController
                                        └─► #11 OtpVerificationService + OTP endpoints
                                             └─► #12 StationDispatchController
                                                  └─► #13 TileQueueDepthPublisher + DeferredRetryJob
                                                       └─► #14 Circuit breakers + DLQ replay
                                                            └─► #15 Metrics + health
```

---

## Open blockers — raise these before Phase 3

| Item | Needed before | Action |
|------|--------------|--------|
| **`OsrmRoutingPort`** public interface in `com.oneday.grid.service` — **still absent (the real blocker)** | Phase 3 (PR #6) | Raise with M3 team now; or fall back to calling OSRM `/route` over HTTP (addendum §6) |
| **Tile queue-depth producer ownership** — M4 vs M5 (M3's consumer reads `orders.tile_queue_depth`, attributed to M4) | Phase 7 (PR #13) | Confirm with M3/M4 owners; if M5, publish to existing `EventStreams.TILE_QUEUE_DEPTH` (addendum §4) |
| Q-M4-5 — OD-8 delivery verification (OTP vs QR) | Phase 5 (PR #10) | Raise with M4 team urgently — shapes the `drop-completed` endpoint |
| ~~Q-M6-1 — cron event format~~ | — | ✅ **RESOLVED** — `DaCronScheduledEvent` on `CRON_EVENTS` (addendum §2) |
| ~~Q-M3-1 / Q-M3-4 — M3 controllers / `TileQueueDepthConsumer`~~ | — | ✅ **RESOLVED** — both live (addendum §4, §5) |
