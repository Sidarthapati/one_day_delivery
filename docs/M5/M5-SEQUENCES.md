# M5 — Sequence Diagrams

> Extracted from [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) §6, §8, §9, §11, §12.

---

## 1. Pickup Assignment — Happy Path

Triggered when M4 emits `ShipmentCreatedEvent` for a `DA_PICKUP` shipment.

```mermaid
sequenceDiagram
    participant Kafka
    participant M5C as M5 ShipmentCreatedConsumer
    participant M3
    participant M5D as M5 DispatchService
    participant M5F as M5 CronFeasibilityService
    participant DB
    participant DaApp as DA App

    Kafka-->>M5C: ShipmentCreatedEvent (pickupType=DA_PICKUP)
    M5C->>M3: GET /grid/tile-at?city={}&lat={}&lon={}
    M3-->>M5C: TileAtResponse {tileId, active=true}
    M5C->>DB: SELECT active DAs for tile (da_tile_assignment, today)
    DB-->>M5C: daId list — pick DA with min queue_depth
    M5C->>M5D: assignPickup(shipmentId, tileId, lat, lon, paymentMode)
    M5D->>M5D: acquire daLock(daId)
    M5D->>M5F: checkFeasibility(daQueue, newPickup, cronAssignment)
    M5F->>M5F: Haversine fast-path for each insertion position
    Note over M5F: cronSlack >= 20 min — OSRM not needed
    M5F-->>M5D: feasible=true, insertAt=1, cronSlackSec=2400
    M5D->>DB: INSERT dispatch_queue at position 1
    M5D->>DB: UPDATE queue_position+1 for rows after insertion
    M5D->>DB: INSERT da_assignment_audit (decision=ASSIGNED)
    M5D->>M5D: update in-memory DaQueue
    M5D->>M5D: release daLock(daId)
    M5D->>Kafka: DaEvent(PICKUP_ASSIGNED, taskId, pickupEta, queuePosition=1)
    Kafka-->>DaApp: QUEUE_REORDERED — DA app re-renders stop list
    Kafka-->>M4: BOOKED → PICKUP_ASSIGNED (side-effect: OTP sent to customer)
```

> ↩ **Return to implementation plan:** [Phase 3 — PR #6 (CronFeasibilityService)](M5-Implementation-Plan.md#phase-3-pr6-build) · [Phase 3 — PR #7 (DispatchServiceImpl)](M5-Implementation-Plan.md#phase-3-pr7-build)

---

## 2. Pickup Assignment — Borderline Cron (OSRM Confirmation)

Same as above but cron slack is tight, triggering the OSRM slow path.

```mermaid
sequenceDiagram
    participant M5D as M5 DispatchService
    participant M5F as M5 CronFeasibilityService
    participant OSRM
    participant Kafka
    participant DB

    M5D->>M5F: checkFeasibility(daQueue, newPickup, cronAssignment)
    M5F->>M5F: Haversine fast-path — cronSlack = 14 min (below 20 min threshold)
    M5F->>OSRM: GET /route?waypoints=[P0,P_new,P1...Pn,P_cron]
    OSRM-->>M5F: total_duration_seconds = 3180
    M5F->>M5F: T_current + 3180 <= scheduled_meeting_time? YES
    M5F-->>M5D: feasible=true, insertAt=2, cronSlackSec=420, usedOsrm=true
    M5D->>DB: INSERT dispatch_queue
    M5D->>DB: INSERT da_assignment_audit (usedOsrm=true)
    M5D->>Kafka: DaEvent(PICKUP_ASSIGNED)
```

> ↩ **Return to implementation plan:** [Phase 3 — PR #6 (CronFeasibilityService)](M5-Implementation-Plan.md#phase-3-pr6-build)

---

## 3. Pickup Assignment — Cron Infeasible → Deferred → Retry

When no insertion position keeps the DA cron-feasible, the order is deferred and retried after the DA's van handoff completes.

```mermaid
sequenceDiagram
    participant Kafka
    participant M5C as M5 ShipmentCreatedConsumer
    participant M5D as M5 DispatchService
    participant M5F as M5 CronFeasibilityService
    participant DB
    participant RetryJob as M5 DeferredRetryJob

    Kafka-->>M5C: ShipmentCreatedEvent (pickupType=DA_PICKUP)
    M5C->>M5D: assignPickup(shipmentId, tileId, coords)
    M5D->>M5D: acquire daLock(daId)
    M5D->>M5F: checkFeasibility(daQueue, newPickup, cronAssignment)
    M5F-->>M5D: feasible=false — all insertion positions breach cron deadline
    M5D->>DB: INSERT deferred_dispatch (reason=CRON_INFEASIBLE, retry_after=cron_time+15min)
    M5D->>DB: INSERT da_assignment_audit (decision=DEFERRED_CRON, da_id_selected=null)
    M5D->>M5D: release daLock(daId)

    Note over DB: Order waits. DA completes pickups and van handoff.

    RetryJob->>DB: SELECT FROM deferred_dispatch WHERE status=PENDING AND retry_after<=now()
    DB-->>RetryJob: deferredRow {shipmentId, tileId, coords}
    RetryJob->>M5D: assignPickup(shipmentId, tileId, coords)
    M5D->>M5F: checkFeasibility(refreshed queue, newPickup, nextCronAssignment)
    M5F-->>M5D: feasible=true (new cron cycle has fresh time budget)
    M5D->>DB: INSERT dispatch_queue
    M5D->>DB: UPDATE deferred_dispatch SET status=ASSIGNED
    M5D->>Kafka: DaEvent(PICKUP_ASSIGNED)
```

> ↩ **Return to implementation plan:** [Phase 3 — PR #7 (DispatchServiceImpl)](M5-Implementation-Plan.md#phase-3-pr7-build) · [Phase 4 — PR #8 (ShipmentCreatedConsumer)](M5-Implementation-Plan.md#phase-4-pr8-build) · [Phase 7 — PR #13 (DeferredRetryJob)](M5-Implementation-Plan.md#phase-7-pr13-build)

---

## 4. OTP Verification (Pickup Confirmation)

The DA arrives at the customer's location and verifies the customer-held OTP.

```mermaid
sequenceDiagram
    participant DaApp as DA App
    participant M5 as M5 API
    participant M4 as M4 Internal
    participant Kafka
    participant M10

    DaApp->>M5: POST /dispatch/da/{id}/tasks/{id}/verify-otp {otp: "4821"}
    M5->>M4: POST /internal/v1/shipments/{ref}/pickup-otp/verify {otp, daId}

    alt OTP correct
        M4-->>M5: 200 OK
        Note over M4: M4 sets PICKUP_ASSIGNED → PICKED_UP internally
        M5->>Kafka: DaEvent(PICKUP_COMPLETED)
        Kafka-->>M10: open SLA Leg 1 start timestamp
        M5-->>DaApp: 200 {next_action: "WAIT_FOR_VAN"}
    else OTP wrong
        M4-->>M5: 422 {error: "OTP_INVALID"}
        M5-->>DaApp: 422 {error: "OTP_INVALID"}
        Note over DaApp: DA asks customer to check SMS
    else OTP expired
        M4-->>M5: 422 {error: "OTP_EXPIRED"}
        DaApp->>M5: POST /dispatch/da/{id}/tasks/{id}/resend-otp
        M5->>M4: POST /internal/v1/shipments/{ref}/pickup-otp/resend
        M4-->>M5: 200 {resends_remaining: 2}
        M5-->>DaApp: 200 {resends_remaining: 2}
    else Max resends exceeded
        M4-->>M5: 422 {error: "MAX_RETRIES_EXCEEDED"}
        M5-->>DaApp: 422 {error: "MAX_RETRIES_EXCEEDED"}
        Note over DaApp: DA reports pickup failure
    end
```

> ↩ **Return to implementation plan:** [Phase 5 — PR #11 (OtpVerificationService)](M5-Implementation-Plan.md#phase-5-pr11-build)

---

## 5. Van Handoff (Cron Meeting)

The DA reaches the cron vertex and physically hands parcels to the hub consolidation van.

```mermaid
sequenceDiagram
    participant DaApp as DA App
    participant M5 as M5 API
    participant DB
    participant Kafka
    participant M4
    participant M10
    participant RetryJob as M5 DeferredRetryJob

    DaApp->>M5: POST /dispatch/da/{id}/tasks/{id}/van-handoff {parcel_scan, van_id, timestamp}
    M5->>DB: UPDATE dispatch_queue SET status=COMPLETED, completed_at=now()
    M5->>DB: UPDATE da_cron_assignment SET status=COMPLETED, handoff_completed_at, parcel_count
    M5->>DB: UPDATE da_status SET status=IDLE
    M5->>Kafka: DaEvent(VAN_HANDOFF_COMPLETED, vanId, parcelScan, parcelCount=4)
    Kafka-->>M4: PICKED_UP → HANDED_TO_PICKUP_VAN
    Kafka-->>M10: SLA Leg 1 closed
    M5->>RetryJob: trigger retry for DA tile (deferred orders)
    RetryJob->>DB: SELECT pending deferred_dispatch for tile WHERE retry_after<=now()
    DB-->>RetryJob: 2 deferred orders found
    RetryJob->>M5: assignPickup x2 (new cron cycle for next shift or same-day extension)
    M5-->>DaApp: 200 {next_task: {task_id, address, eta}, deferred_orders_released: 2}
```

> ↩ **Return to implementation plan:** [Phase 5 — PR #10 (DaDispatchController)](M5-Implementation-Plan.md#phase-5-pr10-build)

---

## 6. Delivery Assignment — Full Last-Mile Flow

Triggered when M7 loads a parcel onto the drop van at the destination hub.

```mermaid
sequenceDiagram
    participant M7
    participant M4
    participant Kafka
    participant M5C as M5 StateChangedConsumer
    participant M3
    participant M5D as M5 DispatchService
    participant DB
    participant DaApp as DA App

    M7->>M4: Drop van handoff recorded
    M4->>Kafka: ShipmentStateChangedEvent (state=HANDED_TO_DROP_VAN, dropType=DA_DELIVERY)
    Kafka-->>M5C: deliver event
    M5C->>M3: GET /grid/tile-at?lat={destLat}&lon={destLon} (destination city)
    M3-->>M5C: TileAtResponse {tileId}
    M5C->>M5D: assignDelivery(shipmentId, destTileId, destLat, destLon)
    M5D->>DB: Find active delivery DA for tile
    M5D->>M5D: cheapest-insertion with hub-return constraint (not cron)
    M5D->>DB: INSERT dispatch_queue (task_type=DELIVERY)
    M5D->>Kafka: DaEvent(DROP_ASSIGNED, deliveryEta, queuePosition)
    Kafka-->>M4: HANDED_TO_DROP_VAN → DROP_ASSIGNED
    Kafka-->>DaApp: QUEUE_REORDERED

    Note over DaApp,M5D: DA drives to drop van location

    DaApp->>M5D: POST /dispatch/da/{id}/tasks/{id}/drop-collected {parcel_scan}
    M5D->>DB: UPDATE dispatch_queue SET status=IN_PROGRESS
    M5D->>Kafka: DaEvent(DROP_COLLECTED, parcelScan)
    Kafka-->>M4: DROP_ASSIGNED → DROP_COLLECTED

    Note over DaApp,M5D: DA drives to customer address

    DaApp->>M5D: POST /dispatch/da/{id}/tasks/{id}/drop-completed {cod_collected_paise: null}
    M5D->>DB: UPDATE dispatch_queue SET status=COMPLETED
    M5D->>Kafka: DaEvent(DROP_COMPLETED)
    Kafka-->>M4: DROP_COLLECTED → DROPPED
```

---

## 7. Delivery Failure → M11 Escalation

```mermaid
sequenceDiagram
    participant DaApp as DA App
    participant M5 as M5 API
    participant DB
    participant Kafka
    participant M4
    participant M11

    DaApp->>M5: POST /dispatch/da/{id}/tasks/{id}/failed {reason_code: "CUSTOMER_ABSENT"}
    M5->>DB: UPDATE dispatch_queue SET status=FAILED
    M5->>Kafka: DaEvent(DROP_FAILED, reasonCode=CUSTOMER_ABSENT)
    Kafka-->>M4: DROP_COLLECTED → DELIVERY_FAILED
    Kafka-->>M11: open failure flow (call center queue, reschedule, or RTO)

    Note over M11: M11 decides: reschedule delivery

    M11->>Kafka: ExceptionsEvent(DELIVERY_RESCHEDULED, shipmentId, newSlot)
    Kafka-->>M5: ExceptionsEventConsumer receives DELIVERY_RESCHEDULED
    M5->>M5: re-run assignDelivery for rescheduled order
    M5->>Kafka: DaEvent(DROP_ASSIGNED) — new DA or same DA
    Kafka-->>M4: DELIVERY_FAILED → DROP_ASSIGNED
```

---

## 8. GPS Heartbeat and Absent DA Detection

```mermaid
sequenceDiagram
    participant DaApp as DA App
    participant M5Api as M5 API
    participant M5Mem as M5 In-Memory State
    participant DB
    participant AbsentJob as M5 AbsentDaDetectionJob
    participant Kafka

    loop Every 30 seconds (normal operation)
        DaApp->>M5Api: POST /dispatch/da/{id}/gps {lat, lon, timestamp}
        M5Api->>M5Mem: updateGps(daId, lat, lon) — in-memory only
        M5Api-->>DaApp: 204 No Content
    end

    Note over M5Mem,DB: @Scheduled every 2 minutes — flush dirty rows
    M5Mem->>DB: batch UPDATE da_status SET last_gps_lat, last_gps_lon, last_heartbeat

    Note over DaApp: DA phone loses signal

    Note over AbsentJob: @Scheduled every 5 minutes
    AbsentJob->>M5Mem: check all active DA last_heartbeat timestamps
    M5Mem-->>AbsentJob: DA xyz — last_heartbeat = 18 min ago
    AbsentJob->>M5Mem: updateStatus(daId, ABSENT)
    AbsentJob->>DB: INSERT deferred_dispatch for DA's QUEUED tasks
    AbsentJob->>Kafka: DaEvent(DA_ABSENT, daId, cityId)
    Kafka-->>M10: escalate to station manager

    Note over DaApp: DA phone comes back online

    DaApp->>M5Api: POST /dispatch/da/{id}/gps {lat, lon, timestamp}
    M5Api->>M5Mem: updateGps → clears ABSENT flag
    M5Mem->>M5Mem: updateStatus(daId, IDLE or IN_PROGRESS)
    M5Mem->>M5Mem: trigger DeferredRetryJob for DA tile
```

> ↩ **Return to implementation plan:** [Phase 2 — PR #4 (DaStatusService + ShiftLoadJob)](M5-Implementation-Plan.md#phase-2-pr4-build)

---

## 9. Shift Load (Start of Day)

Runs 15 minutes before shift start. Initialises all in-memory state for the day.

```mermaid
sequenceDiagram
    participant ShiftJob as M5 ShiftLoadJob
    participant M3
    participant DB
    participant M5Mem as M5 In-Memory State

    Note over ShiftJob: @Scheduled at shift_start_time - 15 min

    ShiftJob->>M3: GET /grid/assignments?city_id=BLR&date=2026-05-09
    M3-->>ShiftJob: [DaAssignmentResponse] — list of DA-to-tile mappings
    ShiftJob->>DB: SELECT da_cron_assignment WHERE operating_date=today AND city_id=BLR
    DB-->>ShiftJob: [DaCronAssignment] — cron vertex + meeting time per DA
    ShiftJob->>DB: SELECT tile_demand_snapshot WHERE date=today AND city_id=BLR
    DB-->>ShiftJob: serviceTimeMin per tile (e.g. 12 min/order default)

    loop For each active DA in city
        ShiftJob->>M5Mem: init DaQueue (empty task list)
        ShiftJob->>M5Mem: init DaLiveStatus (OFFLINE, no GPS yet)
        ShiftJob->>M5Mem: init ReentrantLock for daId
        ShiftJob->>M5Mem: store cronAssignment reference in DaQueue
    end

    ShiftJob->>M5Mem: store tileServiceTimeMap (tile_id → serviceTimeMin)
    Note over M5Mem: M5 is ready to accept orders for the shift
```

> ↩ **Return to implementation plan:** [Phase 2 — PR #4 (DaStatusService + ShiftLoadJob)](M5-Implementation-Plan.md#phase-2-pr4-build)

---

## 10. Cron Freeze and Post-Handoff Queue Release

Shows the state transitions as a DA approaches the cron meeting window.

```mermaid
sequenceDiagram
    participant CronMonitor as M5 Cron Monitor
    participant M5Mem as M5 In-Memory State
    participant DB
    participant Kafka
    participant DaApp as DA App
    participant RetryJob as M5 DeferredRetryJob

    Note over CronMonitor: scheduled_meeting_time - now() drops to 30 min

    CronMonitor->>M5Mem: updateStatus(daId, CRON_LOCKED)
    Note over M5Mem: New pickup assignments for this DA are now rejected

    Note over DB: A new order arrives for DA's tile while CRON_LOCKED
    DB->>DB: INSERT deferred_dispatch (reason=CRON_LOCKED, retry_after=meeting_time+15min)

    Note over DaApp: DA arrives at cron vertex

    DaApp->>M5Mem: POST van-handoff → status = AT_CRON
    M5Mem->>Kafka: DaEvent(VAN_HANDOFF_COMPLETED)
    M5Mem->>M5Mem: updateStatus(daId, IDLE)

    RetryJob->>DB: SELECT deferred_dispatch WHERE reason=CRON_LOCKED AND retry_after<=now()
    DB-->>RetryJob: 3 deferred orders
    RetryJob->>M5Mem: assignPickup x3 (next cron cycle feasibility checked)
    M5Mem->>Kafka: DaEvent(PICKUP_ASSIGNED) x3
    M5Mem-->>DaApp: updated queue pushed to DA app
```
