# M5 — DA Status & Task State Machines

> Extracted from [M5-DISPATCH-DESIGN.md](M5-DISPATCH-DESIGN.md) §7 and §12.  
> Update this file whenever status transitions change. Ops sign-off required before implementation.

---

## Part 1 — DA Status State Machine

### Visual Flow

```
                      ┌─────────────┐
                      │   OFFLINE   │◄────────────────────────────────────────────┐
                      └──────┬──────┘                                             │
                             │  Shift starts + heartbeat received                 │
                             ▼                                                     │
                      ┌─────────────┐                                             │
                      │    IDLE     │◄───────────────────────────────────────┐    │
                      └──────┬──────┘                                        │    │
              ┌──────────────┼──────────────────────────┐                    │    │
              │              │                          │                    │    │
              │ Task         │ Cron freeze              │ Heartbeat          │    │
              │ assigned +   │ window entered           │ timeout            │    │
              │ en-route     │ (≤30 min to cron time)   │ (>15 min)          │    │
              ▼              ▼                          ▼                    │    │
   ┌─────────────────┐ ┌─────────────────┐  ┌──────────────────┐            │    │
   │  IN_PROGRESS    │ │  CRON_LOCKED    │  │     ABSENT       │            │    │
   └────────┬────────┘ └───────┬─────────┘  └────────┬─────────┘            │    │
            │                  │                      │                     │    │
            │ Task completed   │ DA arrives at        │ Heartbeat           │    │
            │ or failed        │ cron vertex          │ resumes             │    │
            ▼                  ▼                      ▼                     │    │
            │           ┌─────────────┐              │                     │    │
            │           │   AT_CRON   │              │                     │    │
            │           └──────┬──────┘              │                     │    │
            │                  │ Van handoff         │                     │    │
            │                  │ completed           │                     │    │
            │                  │                     │                     │    │
            └─────────────────►├◄────────────────────┘                     │    │
                               │                                           │    │
                               │ If cron handoff was the last task for DA: │    │
                               │ return to IDLE (post-cron idle period)    │    │
                               └───────────────────────────────────────────┘    │
                                                                                 │
                      Any status ──► OFFLINE when shift ends ────────────────────┘
```

### Status Reference

| Status | Meaning | Accepts new pickup tasks? |
|--------|---------|--------------------------|
| `OFFLINE` | Not on shift, or no heartbeat received since shift start | No |
| `IDLE` | On shift, heartbeat active, queue empty | Yes |
| `IN_PROGRESS` | Travelling to or servicing a pickup or delivery stop | Yes (inserted behind current task) |
| `CRON_LOCKED` | Within `CRON_FREEZE_MINUTES` (default 30 min) of scheduled cron meeting | No — new tasks deferred |
| `AT_CRON` | DA at cron vertex, actively handing parcels to van | No |
| `ABSENT` | On shift but GPS silent > `ABSENT_THRESHOLD_MINUTES` (default 15 min) | No — tasks for DA's tile deferred |

### Allowed Transitions

```
OFFLINE
  → IDLE         (shift load: DA checked in + first heartbeat received)

IDLE
  → IN_PROGRESS  (DaDispatchController.enRoute called — DA confirms travelling to first stop)
  → CRON_LOCKED  (AbsentDaDetectionJob/cron monitor: scheduled_meeting_time − now() ≤ CRON_FREEZE_MINUTES)
  → ABSENT       (AbsentDaDetectionJob: now() − last_heartbeat > ABSENT_THRESHOLD_MINUTES)
  → OFFLINE      (ShiftEndJob: shift_end_time reached)

IN_PROGRESS
  → IDLE         (task completed or failed; queue empty after completion)
  → IN_PROGRESS  (next task in queue auto-started after current task completed — stays IN_PROGRESS)
  → CRON_LOCKED  (cron monitor fires while task is in-progress)
  → ABSENT       (AbsentDaDetectionJob fires while task is in-progress — unusual; DA unreachable)
  → OFFLINE      (ShiftEndJob — edge case: DA has an in-progress task at shift end time)

CRON_LOCKED
  → AT_CRON      (DA's GPS arrives within radius of cron_vertex_id)
  → ABSENT       (DA goes silent in cron window — triggers CRON_MISSED event)
  → OFFLINE      (ShiftEndJob — edge case: cron window begins after nominal shift end due to ops extension)

AT_CRON
  → IDLE         (van handoff completed; parcels handed; DA has remaining shift time)
  → OFFLINE      (van handoff completed; shift ends; no further tasks)

ABSENT
  → IDLE         (heartbeat resumes; queue was empty)
  → IN_PROGRESS  (heartbeat resumes; DA had tasks in queue)
  → OFFLINE      (ShiftEndJob fires while DA is still ABSENT)
```

Any transition not listed above is rejected; an `IllegalStateException` is logged and an ops alert is emitted.

---

## Part 2 — Task Status State Machine (`DispatchQueue.status`)

### Visual Flow

```
                        ┌──────────┐
                        │  QUEUED  │
                        └────┬─────┘
           ┌─────────────────┼──────────────────────────┐
           │                 │                          │
           ▼                 ▼                          ▼
  ┌───────────────┐  ┌─────────────┐          ┌──────────────────┐
  │  IN_PROGRESS  │  │  CANCELLED  │          │    DEFERRED      │
  └───────┬───────┘  └─────────────┘          └──────────────────┘
    ┌─────┴─────┐
    ▼           ▼
┌─────────┐  ┌────────┐
│COMPLETED│  │ FAILED │
└─────────┘  └────────┘
```

### Transition Triggers

| From | To | Trigger |
|------|----|---------|
| `QUEUED` | `IN_PROGRESS` | DA confirms en-route (`POST /dispatch/da/{id}/tasks/{id}/en-route`) |
| `QUEUED` | `CANCELLED` | M4 emits `ShipmentCancelledEvent`; `ShipmentCancelledConsumer` removes task |
| `QUEUED` | `DEFERRED` | DA enters CRON_LOCKED / ABSENT / SHIFT_ENDED; task cannot be served — moved to `deferred_dispatch` table |
| `IN_PROGRESS` | `COMPLETED` | DA verifies OTP (pickup) or confirms delivery (drop) |
| `IN_PROGRESS` | `FAILED` | DA reports failure via `POST /dispatch/da/{id}/tasks/{id}/failed` |

**No preemption rule:** a task that is `IN_PROGRESS` is **never** moved back to `QUEUED` or reassigned to another DA. The task stays with the current DA to completion or failure. M11 handles any failure recovery.

**DEFERRED rows** are written to the `deferred_dispatch` table with a `defer_reason` and `retry_after` timestamp. `DeferredRetryJob` polls these every 5 minutes and attempts reassignment.

---

## Part 3 — DA Shift Timeline

```
 T−15 min             T=0                    T+shift_hours         T+post-cron
(shift_load_time)   (shift_start)           (~13:30 for Shift A)   (after handoff)
      │                  │                         │                     │
      ▼                  ▼                         ▼                     ▼
 ┌─────────┐       ┌──────────┐             ┌──────────────┐       ┌──────────┐
 │ShiftLoad│       │DA checks │             │CRON_FREEZE   │       │AT_CRON   │
 │  Job    │       │in; IDLE  │             │begins; no new│       │Parcel    │
 │         │       │          │             │pickup tasks  │       │handoff   │
 │reads M3 │       │GPS hearts│             │              │       │          │
 │DA-tile  │       │beat flow │             │              │       │          │
 │map      │       │          │             │              │       │          │
 │reads M6 │       │orders    │             │              │       │          │
 │cron sched│      │arrive →  │             │              │       │          │
 │init mem │       │cheapest  │             │              │       │          │
 │queues   │       │insertion │             │              │       │          │
 └─────────┘       └──────────┘             └──────────────┘       └──────────┘
                                                                          │
                                                                          │ IDLE again
                                                                          │ (deliveries
                                                                          │ may still run
                                                                          │ post-cron)
                                                                          ▼
                                                                   ┌──────────┐
                                                                   │ShiftEnd  │
                                                                   │Job       │
                                                                   │          │
                                                                   │DEFERRED  │
                                                                   │remaining │
                                                                   │tasks     │
                                                                   │OFFLINE   │
                                                                   └──────────┘
```

**Key timing parameters** (all `@ConfigurationProperties`-bound):

| Parameter | Default | Description |
|-----------|---------|-------------|
| `dispatch.cron.freeze-minutes` | 30 | Minutes before cron meeting when DA stops accepting new pickups |
| `dispatch.da.absent-threshold-minutes` | 15 | GPS silence duration before marking DA ABSENT |
| `dispatch.gps.heartbeat-interval-seconds` | 30 | Expected DA app GPS ping cadence |
| `dispatch.gps.flush-interval-seconds` | 120 | How often M5 flushes in-memory GPS state to DB |
| `dispatch.osrm.confirm-threshold-minutes` | 20 | Cron slack threshold below which OSRM is used instead of Haversine |

---

## Part 4 — CRON_MISSED Flow

When a DA fails to reach the cron vertex before `scheduled_meeting_time`:

```
M5 detects cron_missed (AT_CRON not reached by scheduled_meeting_time)
         │
         ├─ Mark DA_CRON_ASSIGNMENT.status = MISSED
         ├─ Publish DaEvent(CRON_MISSED) on oneday.da.events
         │    → M10 consumer: open SLA breach for all shipments still in PICKED_UP state for this DA
         │    → M10: escalate to station manager
         ├─ Van departs without DA's parcels (M6/van driver records the miss independently)
         │
         └─ DA status remains IN_PROGRESS (or CRON_LOCKED) — DA is not OFFLINE
            Parcels still with DA; ops resolution required:
              Option A: DA makes direct hub trip (not a regular cron run)
              Option B: Station manager waits for next van run (if same-day)
```

---

*Document version: 1.0 — extracted from M5-DISPATCH-DESIGN.md v1.1.*
