# M5 — DA Assignment & Dispatch: Design Doc

| Field | Value |
|-------|-------|
| Module | M5 — dispatch |
| Status | Draft v1.1 |
| Author | Design session 2026-05-19 |
| Depends on | common (event contracts), M3 (tile/DA map, OSRM adjacency, load scores), M4 (shipment events), M6 (cron schedule) |
| Consumed by | M4 (state updates via the event bus + OTP endpoint), M3 (tile_queue_depth feed), M10 (SLA leg open/close), M11 (failure flow) |

> ⚠️ **Messaging is RabbitMQ, not Kafka** (codebase refactored since this doc was written). Where the
> text says "Kafka topic" read **`EventStreams` exchange**; "Kafka consumer" → **`@RabbitListener`** on
> a queue declared in a `*MessagingTopology`; producers inject **`EventPublisher.publish(stream, event)`**
> (event implements `DomainEvent`; its `eventTypeName()` is the routing key). The event-driven *design*
> below is unchanged — only the transport differs. See `docs/EVENT-BUS-ARCHITECTURE.md` and
> [KT §1b](M3-M6-KT-AND-HANDS-ON.md#kt-messaging).

---

## Table of Contents

1. [What M5 Does](#1-what-m5-does)
2. [Industry Context](#2-industry-context)
3. [Key Design Decisions](#3-key-design-decisions)
4. [Scope](#4-scope)
5. [Fulfillment Path Routing](#5-fulfillment-path-routing)
6. [Pickup Assignment Flow](#6-pickup-assignment-flow)
7. [DA Priority Queue](#7-da-priority-queue)
8. [Cron-Meeting Feasibility Constraint](#8-cron-meeting-feasibility-constraint)
9. [Cheapest-Insertion Heuristic](#9-cheapest-insertion-heuristic)
10. [Cross-Territory Dispatch](#10-cross-territory-dispatch)
11. [Delivery Assignment Flow (Last-Mile)](#11-delivery-assignment-flow-last-mile)
12. [DA Shift Management](#12-da-shift-management)
13. [COD Handling](#13-cod-handling)
14. [Data Model](#14-data-model)
15. [API Surface](#15-api-surface)
16. [Event Contracts (RabbitMQ)](#16-event-contracts-rabbitmq)
17. [Implementation Notes](#17-implementation-notes)
18. [Open Edge Cases](#18-open-edge-cases)
19. [Out of Scope for v1](#19-out-of-scope-for-v1)

---

## 1. What M5 Does

M5 owns two concerns that share the same algorithmic primitives:

1. **Pickup dispatch (origin side):** Assign each incoming `DA_PICKUP` shipment to the correct DA, insert the task into the DA's priority queue at the optimal position, verify the DA can still honour the hub-consolidation-van cron meeting before committing, and drive the physical pickup custody chain through to `HANDED_TO_PICKUP_VAN`.

2. **Delivery dispatch (destination side):** After a parcel is loaded onto a drop van at the destination hub (`HANDED_TO_DROP_VAN`), assign a delivery DA, guide the DA through collecting the parcel from the van (`DROP_COLLECTED`) and delivering it to the customer (`DROPPED`).

M5 is a **latency-sensitive, always-on background worker**. It reacts to events on `oneday.shipments.events` (from M4) and internal events — delivered over RabbitMQ (see the messaging note above) — maintains per-DA priority queues in memory and DB, and publishes DA lifecycle events to `oneday.da.events`.

M5 does **not** draw or reshape DA territories — that is M3's nightly job. M5 only dispatches work within already-approved territory assignments.

> ↩ **Return to implementation plan:** [Phase 0 — What to build](M5-Implementation-Plan.md#phase-0-build)

---

## 2. Industry Context

| Company | Model | Dispatch heuristic | Cron equivalent |
|---------|-------|--------------------|-----------------|
| **Amazon Flex** | Driver picks own blocks; algorithm assigns within-block route | Intra-block TSP; ML ETA from historical GPS traces | Hub handoff window enforced at block level; driver must return before block end |
| **Swiggy/Zomato** | Geohash assignment; continuous re-assignment per order | Shortest distance from current GPS; reassignment as often as every 60s | None — no hub consolidation |
| **Dunzo/Porter** | Point-to-point courier; closest-available DA | Nearest idle DA from live GPS pool | None |
| **Delhivery** | Pincode-to-DA static assignment; DA manages own sequence manually | No real-time PQ; DA receives daily beat list in the morning | Hub cutoff time enforced as a soft SLA |
| **FedEx (India)** | Territorial beat assignment; route loaded to scanner each morning | Pre-sequenced stop list; deviation requires manager approval | Nightly route locked; driver must return before end-of-day |
| **1DD (ours)** | Fixed tile → DA assignment from M3; dynamic PQ within tile; cron is a hard constraint | Cheapest-insertion into current queue; cron feasibility check before every add | Hub consolidation van has fixed meeting times at grid vertices; DA must physically reach vertex on time |

**Where we are stronger than Indian peers (Delhivery, BlueDart):**
- Real-time queue reordering: Delhivery's morning beat list is static. Our system reorders dynamically as new orders arrive.
- Hard cron constraint with feasibility check: Delhivery's hub cutoff is a soft SLA. Our check prevents assignment if the hub handoff would be missed.

**Where we are weaker than Swiggy:**
- No live preemption: once a DA is en-route to a pickup, we do not pull them back to a closer order.
- No ML ETA: we use road-factor × haversine; Swiggy uses historical GPS traces with ML for corridor-specific accuracy.

---

## 3. Key Design Decisions

This section resolves open questions A1–A3 from PRD §20.3. A4 (flight-bag weight threshold) is not M5's concern — it belongs in M7.

---

### M5-D-001 — DA Assignment Objective Function (resolves A1)

**Decision:** Lexicographic priority with one hard constraint and one soft optimisation goal.

```
Level 1 (HARD):    Cron-meeting feasibility must not be broken.
                   A new order is only assigned if the DA can still reach the hub-
                   consolidation-van cron meeting point on time after adding this order.
                   If no feasible insertion position exists → defer the order.

Level 2 (SOFT):    Among all valid insertion positions, choose the one with minimum
                   cheapest-insertion cost (extra travel time caused by the detour to
                   the new pickup relative to the current queue).

Level 3 (TIEBREAK): If multiple insertion positions have equal detour cost, insert
                    closest to the front of the queue (serve sooner rather than later).
```

The PRD says the cron meeting is a hard constraint ("no assignment otherwise"). Closest-order priority (PRD §7.1) is implemented as a natural consequence of the cheapest-insertion heuristic: the minimum-detour position is also, in almost all cases, the geographically closest insertion point.

---

### M5-D-002 — "Closer" Definition (resolves A2)

**Decision:** "Closer" means the insertion that **minimises added travel time** to the DA's existing route, not simple Euclidean distance from current GPS position.

- If a DA's queue is empty (idle), closest = pickup with smallest travel time from last known GPS.
- If a DA has orders queued, closest = the pickup whose cheapest-insertion cost (§9) is smallest — it fits most naturally into the current sequence.

A pickup 1 km to the north may require a U-turn through a congested junction (12 min); a pickup 2 km to the east may be directly on the planned route (3 min). Cheapest-insertion handles this; simple GPS-proximity does not.

---

### M5-D-003 — Cron Freeze Window (resolves A3 for M5)

**Decision:** M5 stops accepting new orders for a DA when the DA's cron meeting is within `CRON_FREEZE_MINUTES` (default: **30 minutes**) of the current time.

Within the freeze window, the DA is "cron-locked" — their queue is sealed so they can complete remaining pickups and still reach the meeting vertex with buffer. Any new orders for that DA's tile are deferred until after the cron handoff completes.

30 minutes is intentionally conservative; actual travel from anywhere within a 2 km tile is at most ~12 minutes in Indian urban traffic. The buffer absorbs unexpected delays and last-pickup service time. `CRON_FREEZE_MINUTES` is ops-configurable per city.

---

### M5-D-004 — Travel Time Estimation: Fast Path + OSRM Slow Path

**Decision:** Two-tier approach for latency vs. accuracy.

```
Fast path (O(1), used for insertion ranking):
  Haversine distance × ROAD_FACTOR (default: 1.4)
  ÷ AVG_SPEED_KMPH (default: 25 km/h, configurable per city)
  → estimated travel seconds

Slow path (OSRM single route query, used for cron feasibility when borderline):
  When estimated cron slack < OSRM_CONFIRM_THRESHOLD (default: 20 minutes),
  confirm with OSRM before committing the assignment.
```

OSRM is already self-hosted for M3 (same India OSM instance). M5 reuses the same endpoint. An OSRM routing query returns in under 100ms — acceptable for near-real-time assignment.

---

### M5-D-005 — In-Memory Queue, DB-Backed Persistence

**Decision:** Each DA's priority queue is held in-memory (`ConcurrentHashMap<UUID, DaQueue>`) for hot-path reads. Every mutation is also written to `dispatch_queue` in DB synchronously. On restart, queues are rebuilt from DB.

At 10k orders/day across 5 cities, ~2 orders/minute/city, synchronous DB writes are well within capacity. The in-memory cache avoids DB round-trips on the frequent reads (feasibility checks, GPS heartbeats).

---

## 4. Scope

### 4.1 In Scope

- **Pickup dispatch:** `shipment.created` events where `pickupType = DA_PICKUP`. Not SELF_DROP.
- **Delivery dispatch:** `shipment.handed_to_drop_van` events where `dropType = DA_DELIVERY`. Not HUB_COLLECT.
- Per-DA priority queue (dynamic ordering, cheapest-insertion).
- Cron-meeting feasibility check (hard constraint before every pickup assignment).
- Cron freeze window management.
- OTP verification call: M5 calls M4's internal `POST /internal/v1/shipments/{ref}/pickup-otp/verify` endpoint once the DA enters the customer OTP in the DA app.
- DA GPS heartbeat ingestion and status tracking.
- Deferred order queue + retry after cron handoff completes.
- Cross-territory dispatch when a tile is overloaded (§10).
- Tile queue depth publishing to M3 every 5 minutes during shift hours.
- No-DA detection and escalation when tile has no active DA.
- COD flag forwarding to DA app.
- DA absent detection (GPS heartbeat timeout).

### 4.2 Explicitly Out of Scope

- **SELF_DROP path** — sender brings parcel to origin hub directly; M5 is not involved.
- **HUB_COLLECT path** — receiver picks up from destination hub; M5 is not involved.
- Territory definition or reshape — M3.
- Van route computation — M6.
- Barcode attachment — M8 (triggered at pickup completion scan event).
- Failure rescheduling — M11 (re-assignments flow back to M5 via `PICKUP_RESCHEDULED` / `DELIVERY_RESCHEDULED` events from M11).
- COD cash reconciliation and float management — finance system (post-v1).

---

## 5. Fulfillment Path Routing

When M5 receives a `shipment.created` event (on `oneday.shipments.events`), it first reads `pickupType`:

```
pickupType = DA_PICKUP  → run pickup assignment flow (§6)
pickupType = SELF_DROP  → ignore; M4 transitions to AWAITING_SELF_DROP directly; M5 has nothing to do
```

When M5 receives a `shipment.state_changed` event for state `HANDED_TO_DROP_VAN`:

```
dropType = DA_DELIVERY  → run delivery assignment flow (§11)
dropType = HUB_COLLECT  → ignore; M4 transitions to AWAITING_HUB_COLLECT; no DA involved
```

This routing check is the first thing done in each `@RabbitListener` handler. Ignored events are acked immediately with no processing.

> ↩ **Return to implementation plan:** [Phase 4 — PR #8 (ShipmentCreatedConsumer)](M5-Implementation-Plan.md#phase-4-pr8-build)

---

## 6. Pickup Assignment Flow

### 6.1 Happy path

```
M4 emits ShipmentCreatedEvent on oneday.shipments.events
  (pickupType = DA_PICKUP)
         │
         ▼
M5 ShipmentCreatedConsumer.onEvent()
         │
         ├─ 1. Resolve tile
         │      GET /grid/tile-at?city={}&lat={}&lon={} (M3, in-memory cache)
         │
         ├─ 2. Find active DAs for tile (da_tile_assignment, today's date, ACTIVE)
         │      If n_das_on_tile > 1: select DA with smallest queue_depth
         │
         ├─ 3. If no DA for tile
         │      Write DeferredDispatch (reason=NO_DA_AVAILABLE)
         │      event: grid.no_da_alert (M3 GRID_EVENTS exchange) if not already emitted today
         │      STOP
         │
         ├─ 4. If DA is in CRON_FREEZE window (§8.3)
         │      Write DeferredDispatch (reason=CRON_LOCKED, retry_after=cron_time+15min)
         │      STOP
         │
         ├─ 5. Run cheapest-insertion feasibility check (§9)
         │
         ├─ 5a. If infeasible at all insertion positions
         │       Write DeferredDispatch (reason=CRON_INFEASIBLE)
         │       Publish DaEvent(PICKUP_ASSIGNED failed) internal note only — no M4 event
         │       STOP
         │
         └─ 6. Commit assignment
                Insert DispatchQueue row at chosen position
                Resequence existing queue rows (queue_position += 1 for all rows after insertion)
                Update in-memory DaQueue
                Publish DaEvent(eventType=PICKUP_ASSIGNED) on oneday.da.events
                  → M4 consumes: BOOKED → PICKUP_ASSIGNED
                  → Side effect in M4: OTP generated + sent to customer phone
                Publish DaEvent(eventType=QUEUE_REORDERED) on oneday.da.events
                  → DA app re-renders stop list
```

> ↩ **Return to implementation plan:** [Phase 3 — PR #7 (DispatchServiceImpl)](M5-Implementation-Plan.md#phase-3-pr7-build)

### 6.2 OTP pickup flow

After the DA reaches the customer and the customer provides their OTP:

```
DA enters OTP in DA app
         │
         ▼
DA app calls M5: POST /dispatch/da/{da_id}/tasks/{task_id}/verify-otp
  body: { otp: "1234" }
         │
         ▼
M5 calls M4 internal endpoint:
  POST /internal/v1/shipments/{ref}/pickup-otp/verify
  body: { otp: "1234", da_id: "...", task_id: "..." }
         │
         ├─ Success response from M4 → M4 has transitioned PICKUP_ASSIGNED → PICKED_UP
         │
         │  M5 then:
         │  Publish DaEvent(eventType=PICKUP_COMPLETED) on oneday.da.events
         │    → NOT consumed by M4 for state transition (PICKED_UP already set)
         │    → Consumed by M10: SLA leg 1 start timestamp
         │  Return 200 to DA app: { "next_action": "WAIT_FOR_VAN | PROCEED_TO_NEXT" }
         │
         └─ Error from M4 (wrong OTP, expired, max retries exceeded)
            Return 422 to DA app: { "error": "OTP_INVALID | OTP_EXPIRED | MAX_RETRIES_EXCEEDED" }
            DA app shows error; DA requests resend via
              POST /dispatch/da/{da_id}/tasks/{task_id}/resend-otp
```

> ↩ **Return to implementation plan:** [Phase 5 — PR #11 (OtpVerificationService)](M5-Implementation-Plan.md#phase-5-pr11-build)

### 6.3 Van handoff

When the DA scans the parcel QR code in the DA app at the van meeting point:

```
DA scans parcel QR → DA app calls M5: POST /dispatch/da/{da_id}/tasks/{task_id}/van-handoff
  body: { parcel_scan: "BLR-20260509-000042", van_id: "...", timestamp: "..." }
         │
         ▼
M5:
  Mark DispatchQueue task as COMPLETED
  Update DaCronAssignment.status = COMPLETED, handoff_completed_at, parcel_count
  Publish DaEvent(eventType=VAN_HANDOFF_COMPLETED) on oneday.da.events
    → M4 consumes: PICKED_UP → HANDED_TO_PICKUP_VAN
    → M10 consumes: SLA leg 1 closed
  Retry deferred orders for this DA's tile (if any DeferredDispatch.retry_after has passed)
  Return 200: { "next_task": {...} | null, "deferred_orders_released": int }
```

> ↩ **Return to implementation plan:** [Phase 5 — PR #10 (DaDispatchController)](M5-Implementation-Plan.md#phase-5-pr10-build)

---

## 7. DA Priority Queue

### 7.1 Structure

Each DA has one active queue of `DispatchTask` items. A `DispatchTask` is either a PICKUP or DELIVERY task for a single shipment. Tasks are sequenced so that traversing them in order minimises total added travel time while keeping the cron meeting feasible (the cheapest-insertion invariant).

**Queue invariant for pickup queues:** the implicit last stop is always the cron meeting vertex. All PICKUP tasks are inserted before this virtual last stop.

**Queue invariant for delivery queues:** the implicit last stop is the DA's daily hub-return point (soft constraint — see §11.2).

### 7.2 Task status transitions

```
QUEUED → IN_PROGRESS (DA confirms en-route)
       → COMPLETED   (DA confirmed pickup/delivery + scan/OTP)
       → FAILED      (DA reports failure)
       → DEFERRED    (removed from queue — cron no longer feasible at this position;
                      becomes a DeferredDispatch row)
       → CANCELLED   (shipment was cancelled while task was queued)
```

### 7.3 Reordering trigger

When a new task is inserted at position k, all tasks at positions ≥ k shift down by 1. The DA app receives `DaEvent(QUEUE_REORDERED)` with the full new sequence. **No preemption:** an IN_PROGRESS task is never moved; only QUEUED tasks are reordered.

---

## 8. Cron-Meeting Feasibility Constraint

### 8.1 What the cron is

The "cron" is the scheduled meeting between a DA and the hub consolidation van at a specific grid vertex. M6 plans the van's route nightly and publishes each DA's cron schedule via the **`DaCronScheduledEvent`** (`CronEventType.DA_CRON_SCHEDULED` on `oneday.cron.events`) at plan time — **now implemented** (it also persists `da_cron_schedule` and serves `GET /routing/cron/da/{daId}`). M5 stores this in `da_cron_assignment`. Note the event carries a **list** of meeting times (M6-D-008):

- `cron_vertex_id` — grid vertex where the van will be
- `meeting_lat`, `meeting_lon` — the vertex coordinates (from M3's `grid_vertex`)
- `scheduled_meeting_time` — the latest wall-clock time the DA can arrive and still hand off

### 8.2 Feasibility definition

A queue is **cron-feasible** if:

```
T_current + Σ [ travel(P_i → P_{i+1}) + service_time(P_{i+1}) ] + travel(P_n → P_cron)
≤ scheduled_meeting_time

Where:
  P_0 = DA last known GPS (or estimated position if IN_PROGRESS — see §8.4)
  P_1…P_n = QUEUED tasks in order
  P_cron = cron meeting vertex lat/lon
  service_time(P_i) = tile's service_time_per_order from M3 tile_demand_snapshot (cached at shift start)
                      default 12 min if bootstrapped
  travel(A → B) = fast-path estimate (§3.4) unless within OSRM confirm threshold
```

### 8.3 Cron freeze window

When `scheduled_meeting_time − T_current ≤ CRON_FREEZE_MINUTES`, M5 sets `DaStatus.status = CRON_LOCKED` and stops accepting new pickup tasks for this DA. Existing QUEUED tasks are not affected. The freeze window is a buffer — the DA must complete their current queue and travel to the cron vertex.

### 8.4 IN_PROGRESS task handling

If the DA has a task in IN_PROGRESS state (currently travelling to or at a pickup), P_0 is set to the pickup address of the in-progress task and T_current is set to `expected_eta` of that task (estimated time the DA will complete the in-progress stop). This gives a conservative feasibility estimate.

### 8.5 If cron is missed

1. M5 emits `DaEvent(eventType=CRON_MISSED)` on `oneday.da.events`.
2. Van departs without the DA's parcels (M6 / van driver records the miss).
3. M10 opens an SLA breach for all affected shipments (still in PICKED_UP state).
4. Station manager is alerted via M10 escalation.
5. Ops resolves manually (direct hub trip by DA or next van run).

> ↩ **Return to implementation plan:** [Phase 3 — PR #6 (CronFeasibilityService)](M5-Implementation-Plan.md#phase-3-pr6-build)

---

## 9. Cheapest-Insertion Heuristic

For DA with current queue `[P_1 … P_n]`, cron vertex `P_cron`, and incoming pickup `P_new`:

### 9.1 Algorithm

```
current_position = da.last_gps (or estimated completion of IN_PROGRESS task)

For each candidate insertion index k ∈ {0, 1, …, n}:
  // k=0: insert before P_1  (P_0 = current_position, P_{n+1} = P_cron)
  // k=n: insert after P_n

  extra_travel = travel(P_k → P_new) + travel(P_new → P_{k+1})
               − travel(P_k → P_{k+1})

  extra_time = extra_travel + service_time(tile_of(P_new))

  arrival_at_cron = T_current + existing_total_time + extra_time
  feasible = (arrival_at_cron ≤ scheduled_meeting_time)

  If feasible → record (k, extra_travel)

Best = candidate with minimum extra_travel
If no candidates → INFEASIBLE → defer order
Else → insert at index Best.k
```

### 9.2 OSRM confirmation for borderline cases

```
if |arrival_at_cron − scheduled_meeting_time| < OSRM_CONFIRM_THRESHOLD_SECONDS (default: 1200):
  osrm_total = call_osrm_route([P_0, P_1…P_new inserted…P_n, P_cron])
  feasible = (T_current + osrm_total ≤ scheduled_meeting_time)
  used_osrm = true
```

OSRM is called only when the fast-path estimate is borderline (within 20 minutes of the cutoff). In practice this fires rarely — most assignments are either clearly feasible or clearly infeasible.

### 9.3 Service time source

`service_time_per_order(tile)` is read from M3's `tile_demand_snapshot` for today's date. M5 caches this map (`tile_id → service_time_min`) in memory at shift start. Default 12 min/order if tile is bootstrapped.

### 9.4 Complexity

O(n) per assignment, where n = queue depth (typically 3–8 tasks). Negligible.

> ↩ **Return to implementation plan:** [Phase 3 — PR #6 (CronFeasibilityService)](M5-Implementation-Plan.md#phase-3-pr6-build)

---

## 10. Cross-Territory Dispatch

### 10.1 What it is

When a tile is overloaded, M5 can route an incoming order to a DA in an adjacent territory without redrawing territory boundaries. This implements M3 §16.4.

### 10.2 Trigger

M5 attempts cross-territory when:
1. A new pickup order for tile T is `CRON_INFEASIBLE` for all DAs in tile T, **and**
2. `adjusted_load_score` from M3 `GET /grid/tiles/{tile_id}/load-score` is ≥ 1.5

If both conditions are met, M5 searches for an adjacent DA with capacity.

### 10.3 Eligible adjacent DA

```
For each DA d_adj whose territory is road-adjacent to tile T:
  1. d_adj must be ACTIVE, not CRON_LOCKED, not ABSENT
  2. Inserting the new order into d_adj's queue must be cron-feasible
  3. d_adj's adjusted_load_score < 0.8 (has spare capacity)

Among eligible candidates → select the one with minimum cheapest-insertion cost
```

### 10.4 Audit

Cross-territory assignments are tagged in `dispatch_queue` with `cross_territory = true` and `home_tile_id` set to the order's originating tile. The station manager dispatch view shows these distinctly.

> ↩ **Return to implementation plan:** [Phase 3 — PR #7 (DispatchServiceImpl)](M5-Implementation-Plan.md#phase-3-pr7-build) · [Phase 6 — PR #12 (StationDispatchController)](M5-Implementation-Plan.md#phase-6-pr12-build)

---

## 11. Delivery Assignment Flow (Last-Mile)

### 11.1 Trigger

When M7 loads a parcel onto the drop van and emits `HubEvent(DROP_VAN_HANDOFF)` on `oneday.hub.events`, M4 transitions to `HANDED_TO_DROP_VAN` and re-emits a `ShipmentStateChangedEvent` on `oneday.shipments.events`. M5 consumes this state change and — if `dropType = DA_DELIVERY` — runs delivery assignment.

```
M4 emits ShipmentStateChangedEvent (state=HANDED_TO_DROP_VAN, dropType=DA_DELIVERY)
         │
         ▼
M5 ShipmentStateChangedConsumer.onEvent()
         │
         ├─ Resolve tile for delivery address (same M3 API as pickup)
         ├─ Find active delivery DA for tile at destination city
         ├─ Run cheapest-insertion (same algorithm; cron constraint replaced by hub-return constraint)
         └─ Publish DaEvent(eventType=DROP_ASSIGNED) on oneday.da.events
              → M4: HANDED_TO_DROP_VAN → DROP_ASSIGNED
```

### 11.2 Delivery queue constraint

Delivery DAs do not have a consolidation van cron to honour. Their constraint is the **daily hub-return time** (end-of-shift; e.g., 21:00 IST for Shift B). This is a **soft constraint** (AMBER SLA at 30 minutes before return, RED at 10 minutes) — unlike the pickup cron which is a hard assignment block.

The cheapest-insertion algorithm runs identically, substituting `hub_return_time` for `scheduled_meeting_time`. Infeasibility at hub-return means M5 emits an overload alert to M10 rather than outright deferring the order.

### 11.3 DA collects parcel from van

```
DA scans parcel QR in DA app when collecting from van
         │
         ▼
DA app: POST /dispatch/da/{da_id}/tasks/{task_id}/drop-collected
  body: { parcel_scan: "...", timestamp: "..." }
         │
M5:
  Mark task IN_PROGRESS
  Publish DaEvent(eventType=DROP_COLLECTED) on oneday.da.events
    → M4: DROP_ASSIGNED → DROP_COLLECTED
```

### 11.4 Delivery completion

```
DA confirms delivery in DA app (see OD-8 for verification mechanism — TBD)
         │
         ▼
DA app: POST /dispatch/da/{da_id}/tasks/{task_id}/drop-completed
  body: { cod_collected_paise: integer | null, timestamp: "..." }
         │
M5:
  Mark task COMPLETED
  Publish DaEvent(eventType=DROP_COMPLETED) on oneday.da.events
    → M4: DROP_COLLECTED → DROPPED
  If cod_collected_paise != null: publish DaEvent(eventType=COD_COLLECTED)
```

### 11.5 Delivery failure

```
DA app: POST /dispatch/da/{da_id}/tasks/{task_id}/drop-failed
  body: { reason_code: "CUSTOMER_ABSENT | ...", notes: "..." }
         │
M5:
  Mark task FAILED
  Publish DaEvent(eventType=DROP_FAILED) on oneday.da.events
    → M4: DROP_COLLECTED → DELIVERY_FAILED
    → M11: opens failure flow (call center, reschedule or RTO)
```

---

## 12. DA Shift Management

### 12.1 Shift structure

Each DA works one shift. Shift times are provisioned via HR/ops config (defaults: Shift A 06:00–14:00, Shift B 14:00–22:00 IST; exact times TBD with ops). M5 loads the day's active DA roster at `shift_load_time` (15 minutes before shift start).

At shift load:
1. Read `da_tile_assignment` for today from M3 (REST call: `GET /grid/assignments?city={}&date={}`).
2. Read each DA's cron schedule from `da_cron_assignment` (populated by M6's `cron.scheduled` event at nightly replan).
3. Read `service_time_per_order` per tile from M3 tile_demand_snapshot (REST or DB).
4. Initialise in-memory `DaQueue` objects (empty queues) and `DaStatus` map.

### 12.2 DA status states

| Status | Meaning |
|--------|---------|
| `OFFLINE` | Not on shift or no heartbeat since shift start |
| `IDLE` | On shift, active, queue empty |
| `IN_PROGRESS` | Travelling to or at a pickup/delivery |
| `CRON_LOCKED` | Within `CRON_FREEZE_MINUTES` of scheduled cron meeting |
| `AT_CRON` | At cron vertex, completing handoff |
| `ABSENT` | On shift but GPS silent for > `ABSENT_THRESHOLD_MINUTES` (default: 15) |

### 12.3 GPS heartbeat

DA app sends GPS pings every `GPS_HEARTBEAT_INTERVAL_SECONDS` (default: 30s):

```
POST /dispatch/da/{da_id}/gps
Body: { lat, lon, timestamp }
Response: 204 No Content (in-memory update; DB flush is batched every 2 minutes)
```

### 12.4 Absent DA detection

`AbsentDaDetectionJob` runs every 5 minutes during shift hours:

```
if now − da.last_heartbeat > ABSENT_THRESHOLD_MINUTES:
  DaStatus.status = ABSENT
  Publish DaEvent(eventType=DA_ABSENT) on oneday.da.events
  → M3 emits grid.no_da_alert for affected tile(s)
  → M10 escalates to station manager
```

When heartbeat resumes, DaStatus transitions back to IDLE or IN_PROGRESS. Deferred orders for the tile are retried.

### 12.5 Shift end

At `shift_end_time`:
1. Close all QUEUED tasks (mark DEFERRED, reason=SHIFT_ENDED).
2. For each DEFERRED task: publish `DaEvent(eventType=TASK_DEFERRED_SHIFT_ENDED)` for M11.
3. Clear in-memory queue and status for this DA.
4. Flush final `da_status` to DB.

> ↩ **Return to implementation plan:** [Phase 2 — PR #4 (DaStatusService + ShiftLoadJob)](M5-Implementation-Plan.md#phase-2-pr4-build) · [Phase 2 — PR #5 (CronMonitorJob + ShiftEndJob)](M5-Implementation-Plan.md#phase-2-pr5-build)

---

## 13. COD Handling

For COD shipments (`paymentMode = COD` in `ShipmentCreatedEvent`):
1. `DispatchQueue` row carries `payment_mode = COD`.
2. DA app renders "collect ₹X cash" instruction for that task.
3. On delivery completion, DA enters collected amount in DA app.
4. M5 publishes `DaEvent(eventType=COD_COLLECTED, amount_collected_paise=X)`.
5. Finance reconciliation (float management, DA remittance) is out of M5 scope for v1.

---

## 14. Data Model

### 14.1 dispatch_queue

```sql
CREATE TABLE dispatch_queue (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    da_id            UUID NOT NULL,
    city_id          UUID NOT NULL,
    shipment_id      UUID NOT NULL,
    task_type        VARCHAR(20) NOT NULL,     -- PICKUP | DELIVERY
    pickup_lat       DOUBLE PRECISION NOT NULL,
    pickup_lon       DOUBLE PRECISION NOT NULL,
    tile_id          UUID NOT NULL,
    queue_position   INT NOT NULL,
    status           VARCHAR(20) NOT NULL,
    -- QUEUED | IN_PROGRESS | COMPLETED | FAILED | DEFERRED | CANCELLED
    payment_mode     VARCHAR(20),              -- PREPAID | COD
    cross_territory  BOOLEAN NOT NULL DEFAULT FALSE,
    home_tile_id     UUID,                     -- non-null when cross_territory=true
    cron_safe        BOOLEAN NOT NULL,         -- feasibility verified at assignment time
    assigned_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expected_eta     TIMESTAMPTZ,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    operating_date   DATE NOT NULL,
    UNIQUE(da_id, shipment_id, task_type, operating_date)
);

CREATE INDEX idx_dispatch_queue_da_date  ON dispatch_queue (da_id, operating_date, status);
CREATE INDEX idx_dispatch_queue_shipment ON dispatch_queue (shipment_id);
```

Rows are never deleted (append-only audit trail per XC-D-002).

> ↩ **Return to implementation plan:** [Phase 1 — PR #2 (Flyway migrations)](M5-Implementation-Plan.md#phase-1-pr2-build)

### 14.2 da_cron_assignment

```sql
CREATE TABLE da_cron_assignment (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    da_id                  UUID NOT NULL,
    city_id                UUID NOT NULL,
    operating_date         DATE NOT NULL,
    cron_vertex_id         UUID NOT NULL,   -- FK to M3 grid_vertex
    meeting_lat            DOUBLE PRECISION NOT NULL,
    meeting_lon            DOUBLE PRECISION NOT NULL,
    scheduled_meeting_time TIMESTAMPTZ NOT NULL,
    van_id                 UUID,
    status                 VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    -- SCHEDULED | COMPLETED | MISSED | CANCELLED
    handoff_completed_at   TIMESTAMPTZ,
    parcel_count_handed    INT,
    UNIQUE(da_id, operating_date)
);
```

### 14.3 da_status

One row per DA. Mutable; authoritative state is in-memory and flushed to DB every 2 minutes.

```sql
CREATE TABLE da_status (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    da_id            UUID NOT NULL UNIQUE,
    city_id          UUID NOT NULL,
    shift_date       DATE NOT NULL,
    shift_type       VARCHAR(20),
    status           VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
    last_gps_lat     DOUBLE PRECISION,
    last_gps_lon     DOUBLE PRECISION,
    current_tile_id  UUID,
    queue_depth      INT NOT NULL DEFAULT 0,
    last_heartbeat   TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 14.4 deferred_dispatch

```sql
CREATE TABLE deferred_dispatch (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id        UUID NOT NULL,
    shipment_id    UUID NOT NULL,
    task_type      VARCHAR(20) NOT NULL,
    tile_id        UUID NOT NULL,
    pickup_lat     DOUBLE PRECISION NOT NULL,
    pickup_lon     DOUBLE PRECISION NOT NULL,
    defer_reason   VARCHAR(50) NOT NULL,
    -- NO_DA_AVAILABLE | CRON_INFEASIBLE | CRON_LOCKED | DA_ABSENT | SHIFT_ENDED
    deferred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    retry_after    TIMESTAMPTZ,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- PENDING | ASSIGNED | ESCALATED | EXPIRED
    assigned_at    TIMESTAMPTZ,
    escalated_at   TIMESTAMPTZ,
    operating_date DATE NOT NULL
);

CREATE INDEX idx_deferred_retry ON deferred_dispatch (city_id, status, retry_after)
    WHERE status = 'PENDING';
```

### 14.5 da_assignment_audit

Append-only decision log for every assignment attempt.

```sql
CREATE TABLE da_assignment_audit (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id               UUID NOT NULL,
    task_type                 VARCHAR(20) NOT NULL,
    city_id                   UUID NOT NULL,
    tile_id                   UUID NOT NULL,
    da_id_selected            UUID,
    decision                  VARCHAR(40) NOT NULL,
    -- ASSIGNED | CROSS_TERRITORY_ASSIGNED | DEFERRED_NO_DA | DEFERRED_CRON | DEFERRED_FROZEN
    insertion_pos             INT,
    cheapest_insert_extra_sec INT,
    cron_slack_sec            INT,
    used_osrm                 BOOLEAN NOT NULL DEFAULT FALSE,
    decided_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

> ↩ **Return to implementation plan:** [Phase 1 — PR #2 (Flyway migrations)](M5-Implementation-Plan.md#phase-1-pr2-build) · [Phase 1 — PR #3 (JPA entities)](M5-Implementation-Plan.md#phase-1-pr3-build)

---

## 15. API Surface

### 15.1 DA GPS heartbeat

```
POST /dispatch/da/{da_id}/gps
Auth: DA role JWT
Body: { "lat": double, "lon": double, "timestamp": ISO8601 }
Response: 204 No Content
```

### 15.2 DA confirms en-route to stop

```
POST /dispatch/da/{da_id}/tasks/{task_id}/en-route
Auth: DA role JWT
Response: 200 { "task_id", "address", "eta_seconds" }
```

Updates task to IN_PROGRESS. No event emitted — informational only.

### 15.3 DA verifies customer OTP (pickup only)

```
POST /dispatch/da/{da_id}/tasks/{task_id}/verify-otp
Auth: DA role JWT
Body: { "otp": "1234" }
Response:
  200: { "next_action": "WAIT_FOR_VAN | PROCEED_TO_NEXT" }
  422: { "error": "OTP_INVALID | OTP_EXPIRED | MAX_RETRIES_EXCEEDED" }
```

M5 calls M4's internal OTP-verify endpoint. On success, M4 sets `PICKED_UP`; M5 emits `PICKUP_COMPLETED` to M10.

### 15.4 DA requests OTP resend

```
POST /dispatch/da/{da_id}/tasks/{task_id}/resend-otp
Auth: DA role JWT
Response: 200 { "resends_remaining": int }
```

Delegates to M4's `POST /internal/v1/shipments/{ref}/pickup-otp/resend`.

### 15.5 DA completes van handoff (pickup)

```
POST /dispatch/da/{da_id}/tasks/{task_id}/van-handoff
Auth: DA role JWT
Body: { "parcel_scan": "...", "van_id": "...", "timestamp": ISO8601 }
Response: 200 { "next_task": {...} | null, "deferred_orders_released": int }
```

Emits `VAN_HANDOFF_COMPLETED`.

### 15.6 DA reports pickup/delivery failure

```
POST /dispatch/da/{da_id}/tasks/{task_id}/failed
Auth: DA role JWT
Body: { "reason_code": "CUSTOMER_ABSENT | ADDRESS_NOT_FOUND | ACCESS_DENIED | OTHER", "notes": string | null }
Response: 200
```

Task type determines whether `PICKUP_FAILED` or `DROP_FAILED` is emitted.

### 15.7 DA collects parcel from drop van (delivery)

```
POST /dispatch/da/{da_id}/tasks/{task_id}/drop-collected
Auth: DA role JWT
Body: { "parcel_scan": "...", "timestamp": ISO8601 }
Response: 200
```

Emits `DROP_COLLECTED`.

### 15.8 DA confirms delivery

```
POST /dispatch/da/{da_id}/tasks/{task_id}/drop-completed
Auth: DA role JWT
Body: { "cod_collected_paise": integer | null, "timestamp": ISO8601 }
Response: 200
```

Emits `DROP_COMPLETED` (and `COD_COLLECTED` if COD).

### 15.9 Station manager: tile dispatch view

```
GET /dispatch/tiles/{tile_id}/queue?date=YYYY-MM-DD
Auth: STATION_MANAGER or ADMIN
Response: {
  "tile_id": "...",
  "operating_date": "...",
  "das": [
    {
      "da_id": "...",
      "status": "IN_PROGRESS",
      "queue_depth": 4,
      "cron_slack_minutes": 42,
      "queue": [
        { "task_id", "shipment_id", "queue_position", "status", "expected_eta",
          "cross_territory", "task_type" }
      ]
    }
  ],
  "deferred_count": 2
}
```

> ↩ **Return to implementation plan:** [Phase 5 — PR #10 (DaDispatchController)](M5-Implementation-Plan.md#phase-5-pr10-build) · [Phase 6 — PR #12 (StationDispatchController)](M5-Implementation-Plan.md#phase-6-pr12-build)

---

## 16. Event Contracts (RabbitMQ)

All M5-produced events go on the single exchange `oneday.da.events` (`EventStreams.DA_EVENTS`; `KafkaTopics` was renamed to **`common.kafka.EventStreams`**). Each event is a `DomainEvent` record carrying a `DaEventType` discriminator (the `eventTypeName()` routing key) matching the enum in `common/kafka/enums/DaEventType.java` — **which already exists** with the M4-consumed values; M5 only adds the 5 internal values.

The tile-queue-depth feed does **not** need a new constant: M3's consumer already listens on the existing `EventStreams.TILE_QUEUE_DEPTH` (`"orders.tile_queue_depth"`) reading a `TileQueueDepthEvent(cityId, tileId, unservedOrders, bookedOrders, date)`. M5 publishes that same payload to that exchange — **pending confirmation of M4-vs-M5 producer ownership** (the code currently attributes it to M4). See [KT §7.3](M3-M6-KT-AND-HANDS-ON.md#kt-gives).

### 16.1 Events consumed by M5

| Topic | Producer | DaEventType / ShipmentEventType | Action in M5 |
|-------|----------|---------------------------------|--------------|
| `oneday.shipments.events` | M4 | `CREATED` (pickupType=DA_PICKUP) | Run pickup assignment |
| `oneday.shipments.events` | M4 | `STATE_CHANGED` (state=HANDED_TO_DROP_VAN, dropType=DA_DELIVERY) | Run delivery assignment |
| `oneday.shipments.events` | M4 | `CANCELLED` | Remove task from DA queue |
| `oneday.cron.events` | M6 | `DA_CRON_SCHEDULED` (plan-time; `DaCronScheduledEvent`) | Populate `da_cron_assignment` with the DA's vertex + **list** of meeting times |
| `oneday.exceptions.events` | M11 | `PICKUP_RESCHEDULED` | Re-run pickup assignment for rescheduled order |
| `oneday.exceptions.events` | M11 | `DELIVERY_RESCHEDULED` | Re-run delivery assignment for rescheduled order |

> Note: M6 populates `da_cron_assignment` by emitting `DA_CRON_SCHEDULED` (`DaCronScheduledEvent`, now in `CronEventType`) at nightly replan time, not at physical cron departure time. M5 may instead read M6's `da_cron_schedule` table or `GET /routing/cron/da/{daId}`. See §12.1.

> ↩ **Return to implementation plan:** [Phase 4 — PR #8 (ShipmentCreatedConsumer)](M5-Implementation-Plan.md#phase-4-pr8-build) · [Phase 4 — PR #9 (StateChangedConsumer)](M5-Implementation-Plan.md#phase-4-pr9-build)

### 16.2 Events produced by M5

All events extend a common `DaEventBase` structure (to be added to `common` module alongside `BaseShipmentEvent`):

```java
// To be added to common:
public abstract class DaEventBase {
    UUID   eventId;
    String eventType;        // DaEventType.name()
    String schemaVersion = "1.0";
    Instant occurredAt;
    UUID   shipmentId;
    String shipmentRef;
    UUID   daId;
    String cityId;
}
```

#### PICKUP_ASSIGNED

```json
{
  "eventType":     "PICKUP_ASSIGNED",
  "shipmentId":    "...",
  "shipmentRef":   "1DD-BLR-20260509-00042",
  "daId":          "...",
  "cityId":        "BLR",
  "taskId":        "...",
  "pickupEta":     "2026-05-09T10:45:00+05:30",
  "queuePosition": 2,
  "crossTerritory": false,
  "occurredAt":    "..."
}
```
M4 consumer: `BOOKED → PICKUP_ASSIGNED` (side-effect: OTP generated and sent to customer)

#### PICKUP_COMPLETED

```json
{
  "eventType":  "PICKUP_COMPLETED",
  "shipmentId": "...",
  "daId":       "...",
  "occurredAt": "..."
}
```
NOT consumed by M4 for state transition (`PICKED_UP` set via OTP-verify HTTP endpoint).
Consumed by M10: SLA leg 1 start.

#### PICKUP_FAILED

```json
{
  "eventType":  "PICKUP_FAILED",
  "shipmentId": "...",
  "daId":       "...",
  "reasonCode": "CUSTOMER_ABSENT",
  "notes":      "Called 3 times, no answer",
  "occurredAt": "..."
}
```
M4 consumer: `PICKUP_ASSIGNED → PICKUP_FAILED`
M11 consumer: opens failure flow

#### VAN_HANDOFF_COMPLETED

```json
{
  "eventType":     "VAN_HANDOFF_COMPLETED",
  "shipmentId":    "...",
  "daId":          "...",
  "vanId":         "...",
  "parcelScan":    "BLR-20260509-000042",
  "parcelCount":   5,
  "occurredAt":    "..."
}
```
M4 consumer: `PICKED_UP → HANDED_TO_PICKUP_VAN`
M10 consumer: SLA leg 1 closed

#### DROP_ASSIGNED

```json
{
  "eventType":     "DROP_ASSIGNED",
  "shipmentId":    "...",
  "daId":          "...",
  "deliveryEta":   "2026-05-09T17:30:00+05:30",
  "queuePosition": 1,
  "occurredAt":    "..."
}
```
M4 consumer: `HANDED_TO_DROP_VAN → DROP_ASSIGNED`

#### DROP_COLLECTED

```json
{
  "eventType":  "DROP_COLLECTED",
  "shipmentId": "...",
  "daId":       "...",
  "parcelScan": "...",
  "occurredAt": "..."
}
```
M4 consumer: `DROP_ASSIGNED → DROP_COLLECTED`

#### DROP_COMPLETED

```json
{
  "eventType":  "DROP_COMPLETED",
  "shipmentId": "...",
  "daId":       "...",
  "occurredAt": "..."
}
```
M4 consumer: `DROP_COLLECTED → DROPPED`

#### DROP_FAILED

```json
{
  "eventType":  "DROP_FAILED",
  "shipmentId": "...",
  "daId":       "...",
  "reasonCode": "CUSTOMER_ABSENT",
  "occurredAt": "..."
}
```
M4 consumer: `DROP_COLLECTED → DELIVERY_FAILED`
M11 consumer: opens failure/RTO flow

### 16.3 dispatch.tile_queue_depth (M5 → M3)

Topic: `oneday.dispatch.tile_queue_depth`  
Published by: `TileQueueDepthPublisher` (@Scheduled every 5 minutes during shift hours)

```json
{
  "eventType":     "TILE_QUEUE_DEPTH_SNAPSHOT",
  "cityId":        "BLR",
  "operatingDate": "2026-05-09",
  "snapshotTime":  "2026-05-09T10:30:00+05:30",
  "tileDepths": [
    { "tileId": "...", "unservedOrders": 7, "inProgressOrders": 2 },
    { "tileId": "...", "unservedOrders": 0, "inProgressOrders": 1 }
  ]
}
```

Full-city snapshot (not per-tile events). M3 replaces its in-memory load-score map entirely on each receipt.

> ↩ **Return to implementation plan:** [Phase 0 — What to build (PR #1)](M5-Implementation-Plan.md#phase-0-build) · [Phase 7 — PR #13 (TileQueueDepthPublisher)](M5-Implementation-Plan.md#phase-7-pr13-build)

### 16.4 DaEventType additions needed in common

The existing `DaEventType` enum (from `satvik/m4-pr2-kafka-contracts-in-common`) covers the M4-consumed events. M5 also needs to emit the following types that are not yet in the enum — these need to be added before M5 implementation begins:

```java
// Additional values to add to DaEventType.java:
QUEUE_REORDERED,     // DA app consumer — re-renders stop list; M4 does not consume
DA_ABSENT,           // M10/M3 consumer — no M4 consumption
CRON_MISSED,         // M10 consumer — SLA breach
COD_COLLECTED,       // Finance consumer — no M4 consumption
TASK_DEFERRED_SHIFT_ENDED  // M11 consumer — reschedule flow
```

> ↩ **Return to implementation plan:** [Phase 0 — What to build (PR #1)](M5-Implementation-Plan.md#phase-0-build)

---

## 17. Implementation Notes

### 17.1 Package layout

```
com.oneday.dispatch/
  api/
    DaDispatchController.java         -- GPS, task lifecycle, OTP endpoints
    StationDispatchController.java    -- station manager tile queue view
  service/
    DispatchService.java              -- interface: assignPickup, assignDelivery
    DispatchServiceImpl.java          -- assignment + cheapest-insertion (package-private)
    CronFeasibilityService.java       -- interface
    CronFeasibilityServiceImpl.java   -- fast-path + OSRM slow-path (package-private)
    DaStatusService.java              -- interface: updateGps, updateStatus, detectAbsent
    DaStatusServiceImpl.java          -- in-memory state + DB flush (package-private)
    DeferredRetryService.java         -- interface: retry, escalate
    DeferredRetryServiceImpl.java     -- retry loop (package-private)
    OtpVerificationService.java       -- interface: verifyOtp, resendOtp
    OtpVerificationServiceImpl.java   -- calls M4 internal HTTP endpoint (package-private)
  domain/
    DispatchQueue.java
    DaCronAssignment.java
    DaStatus.java
    DeferredDispatch.java
    DaAssignmentAudit.java
    DaQueue.java                      -- in-memory value object (not JPA entity)
  repository/
    DispatchQueueRepository.java
    DaCronAssignmentRepository.java
    DaStatusRepository.java
    DeferredDispatchRepository.java
    DaAssignmentAuditRepository.java
  events/
    ShipmentCreatedConsumer.java          -- consumes oneday.shipments.events (CREATED)
    ShipmentStateChangedConsumer.java     -- consumes oneday.shipments.events (STATE_CHANGED)
    ShipmentCancelledConsumer.java        -- consumes oneday.shipments.events (CANCELLED)
    ExceptionsEventConsumer.java          -- consumes oneday.exceptions.events (reschedule)
    DaEventProducer.java                  -- publishes to oneday.da.events
    TileQueueDepthPublisher.java          -- @Scheduled every 5 min → oneday.dispatch.tile_queue_depth
  batch/
    AbsentDaDetectionJob.java             -- @Scheduled every 5 min during shift
    DeferredRetryJob.java                 -- @Scheduled every 5 min during shift
    ShiftLoadJob.java                     -- @Scheduled at shift_load_time
    ShiftEndJob.java                      -- @Scheduled at shift_end_time
  dto/
    GpsUpdateRequest.java
    OtpVerifyRequest.java
    VanHandoffRequest.java
    DropActionRequest.java
    TileQueueResponse.java
```

### 17.2 In-memory state

```java
// Per-DA queue (lock per DA, not global)
ConcurrentHashMap<UUID, DaQueue>     activeQueues;
ConcurrentHashMap<UUID, ReentrantLock> daLocks;

// Per-DA status (GPS, shift state)
ConcurrentHashMap<UUID, DaLiveStatus> daStatuses;
```

Concurrency: `daLocks.get(daId).lock()` is acquired before any queue mutation (insert, reorder, status change). Lock is released immediately after the DB write. This prevents two simultaneously arriving orders from computing stale feasibility for the same DA.

### 17.3 OSRM reuse from M3

M5 uses the same `OsrmClient` Spring bean as M3. No new OSRM infrastructure. The bean is in `com.oneday.grid.service.OsrmMatrixServiceImpl` — M5 declares it as a dependency via the `grid` module and uses the point-to-point route endpoint (not the matrix endpoint).

### 17.4 GPS flush strategy

GPS heartbeats arrive at 30s intervals per DA. Writing every heartbeat synchronously would generate ~3,600 DB writes/hour/50 DAs. Instead:
- In-memory `DaLiveStatus` is updated on every heartbeat.
- A `@Scheduled(fixedDelay = 120_000)` task flushes all dirty rows to `da_status` in a single batched UPDATE.
- Absent-DA detection reads from in-memory (not DB), so the 2-minute flush lag does not affect detection latency.

---

## 18. Open Edge Cases

| # | Scenario | Handling |
|---|----------|----------|
| E1 | **DA queue naturally large** (shift-start backlog) | No hard queue depth cap. Cron feasibility naturally limits growth. If tile is overloaded, M3 overload alert fires and station manager can trigger intraday territory adjustment. |
| E2 | **GPS stale** (DA phone died) | Heartbeat timeout → ABSENT. Orders for tile deferred. Station manager resolves. When heartbeat resumes → retried. |
| E3 | **Tile with `n_das_on_tile > 1`** | M5 assigns to the DA with the smallest `queue_depth`. Both DAs have independent cron assignments for their portion. |
| E4 | **Cron vertex reassigned intraday by M6** | M6 emits a `cron.rescheduled` event. M5 updates `da_cron_assignment` and re-evaluates feasibility for all QUEUED tasks. Orders that become infeasible are moved to `deferred_dispatch`. |
| E5 | **Shipment cancelled while DA is en route** | M4 emits `CANCELLED` event. M5 removes task from queue (CANCELLED status). If DA is already IN_PROGRESS on that task, cancellation is too late operationally — M11 handles. |
| E6 | **`HANDED_TO_DROP_VAN` event for a destination tile with no active DA** | Defer as NO_DA_AVAILABLE. Parcel waits at hub on drop van. Station manager notified via M10. |
| E7 | **OTP expired before DA arrives** | DA uses resend-otp endpoint. M4 generates fresh OTP (max 3 resends per pickup). |
| E8 | **M4 OTP-verify endpoint returns 503** | M5 returns 503 to DA app. DA app shows retry button. M5 retries with exponential backoff (3 attempts; 2s, 4s, 8s). If still failing, emits `PICKUP_FAILED` with reason `OTP_SERVICE_UNAVAILABLE` and alerts ops. |
| E9 | **OSRM is down during assignment** | Circuit breaker opens after 5 failures. Fast-path Haversine with 20% safety buffer continues. `used_osrm = false` in audit log. Ops alerted. |
| E10 | **Two orders arrive simultaneously for the same DA** | Per-DA `ReentrantLock` serialises insertion. Second order waits, then checks feasibility with the queue already containing the first order. |
| E11 | **DA covers multiple tiles (N-tile territory)** | `da_tile_assignment` has multiple rows for same `da_id`. M5 accepts orders from all assigned tiles into a single unified queue. Cron feasibility is shared across all tiles' orders. |
| E12 | **RTO return delivery assignment** | M11 emits event requesting M5 to assign a DA for RTO reverse pickup (return to sender). M5 handles it identically to a regular PICKUP task, using the origin city's DA tile map. |

---

## 19. Out of Scope for v1

- **Live preemption:** pulling a DA off an IN_PROGRESS task to reassign them to a closer order (Swiggy model). Adds significant UX complexity and risk of partial pickups.
- **COD float management and remittance:** M5 records `COD_COLLECTED` events; DA float reconciliation and cash deposit workflows are finance system scope (post-v1).
- **Offline DA app with local scan queue:** DA app requires network in v1 (M8-D-005). M5 receives no GPS heartbeats while offline; absent-DA detection fires after `ABSENT_THRESHOLD_MINUTES`.
- **Automated intraday territory reshaping triggered by M5:** M5 publishes `dispatch.tile_queue_depth` to M3 which handles overload detection, alerting, and optional suggestion (M3 §16.2–16.3). Territory reshape decisions stay with the station manager.
- **DA performance scoring / ranking:** the PQ is demand-driven; no DA reputation or performance score influences assignment order in v1.
- **Delivery time slot enforcement:** if M4 ever adds customer-selected delivery windows, M5 would respect a `not_before` timestamp on delivery tasks. Not in scope for v1.

---

## 20. Interactions with Other Modules

```
M4 (Orders)
  → emits shipment.created (CREATED event, pickupType=DA_PICKUP)
    → M5 triggers pickup assignment
  → emits shipment.state_changed (state=HANDED_TO_DROP_VAN, dropType=DA_DELIVERY)
    → M5 triggers delivery assignment
  → emits shipment.cancelled → M5 removes task from queue
  ← M5 publishes DaEvent(PICKUP_ASSIGNED) → M4: BOOKED → PICKUP_ASSIGNED
  ← M5 calls POST /internal/v1/shipments/{ref}/pickup-otp/verify → M4: PICKUP_ASSIGNED → PICKED_UP
  ← M5 publishes DaEvent(PICKUP_FAILED) → M4: PICKUP_ASSIGNED → PICKUP_FAILED
  ← M5 publishes DaEvent(VAN_HANDOFF_COMPLETED) → M4: PICKED_UP → HANDED_TO_PICKUP_VAN
  ← M5 publishes DaEvent(DROP_ASSIGNED) → M4: HANDED_TO_DROP_VAN → DROP_ASSIGNED
  ← M5 publishes DaEvent(DROP_COLLECTED) → M4: DROP_ASSIGNED → DROP_COLLECTED
  ← M5 publishes DaEvent(DROP_COMPLETED) → M4: DROP_COLLECTED → DROPPED
  ← M5 publishes DaEvent(DROP_FAILED) → M4: DROP_COLLECTED → DELIVERY_FAILED

M3 (Grid)
  ← M5 reads da_tile_assignment at shift load
  ← M5 reads tile service_time_per_order from tile_demand_snapshot at shift load
  ← M5 calls GET /grid/tiles/{tile_id}/load-score for cross-territory eligibility
  → M5 publishes dispatch.tile_queue_depth → M3 updates in-memory load scores
  ← M3 emits grid.no_da_alert → M5 holds assignments for affected tile

M6 (Van Routing)
  → emits cron.scheduled (at nightly replan) → M5 populates da_cron_assignment for each DA

M8 (Barcode)
  ← M5 emits DaEvent(PICKUP_COMPLETED) → M8 records that DA has physical custody (scan event)

M10 (SLA Monitoring)
  ← M5 emits DaEvent(PICKUP_COMPLETED) → M10 opens SLA Leg 1 (pickup → hub)
  ← M5 emits DaEvent(VAN_HANDOFF_COMPLETED) → M10 closes SLA Leg 1
  ← M5 emits DaEvent(DA_ABSENT), DaEvent(CRON_MISSED) → M10 escalates

M11 (Exceptions)
  ← M5 emits DaEvent(PICKUP_FAILED), DaEvent(DROP_FAILED) → M11 opens failure flow
  → M11 emits ExceptionsEvent(PICKUP_RESCHEDULED / DELIVERY_RESCHEDULED) → M5 re-assigns
  → M11 emits RTO request → M5 assigns reverse-direction pickup DA
```

---

*Document version: 1.1 — aligns with `common` Kafka contracts from `satvik/m4-pr2-kafka-contracts-in-common`; resolves PRD §20.3 open questions A1–A3.*
