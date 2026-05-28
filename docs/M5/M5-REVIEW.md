# M5 — Design Review

| Field | Value |
|-------|-------|
| Reviewed by | Claude Code (design session 2026-05-19) |
| Covers | M5-DISPATCH-DESIGN.md v1.1, M5-ER-DIAGRAM.md, M5-IMPL-PLAN.md, M5-SEQUENCES.md, M5-DA-STATUS-MACHINE.md, M5-DEPENDENCY-QUESTIONS.md |
| Status | Open — unresolved items block PR #2 (migrations) |

---

## Summary

| Severity | Count | Items |
|----------|-------|-------|
| Critical bugs | 4 | #1, #2, #3, #4 |
| Architecture violation | 1 | #5 |
| Missing design | 4 | #6, #7, #8, #9 |
| Schema | 3 | #10, #11, #12 |
| Minor | 3 | #13, #14, #15 |

The core algorithm (cheapest-insertion, cron feasibility) is well-specified and the phased implementation plan is solid. Items #1–#5 must be resolved in the design doc before PR #2 (migrations) is written — schema changes after data exists are the most expensive to fix.

---

## Critical Bugs

### #1 — UNIQUE constraint breaks rescheduled pickups

**Location:** `dispatch_queue` schema §14.1; idempotency guard in PR #8

**Problem:** The constraint `UNIQUE(da_id, shipment_id, task_type, operating_date)` means that after a pickup FAILS and M11 emits `PICKUP_RESCHEDULED`, M5's idempotency guard in `ShipmentCreatedConsumer` finds the existing FAILED row and ack-skips it, treating it as a duplicate. Even if the guard is bypassed, the re-insert violates the unique constraint.

**Fix:** Replace the table-level unique constraint with a partial unique index:

```sql
CREATE UNIQUE INDEX idx_dispatch_queue_active_unique
    ON dispatch_queue (da_id, shipment_id, task_type, operating_date)
    WHERE status NOT IN ('FAILED', 'CANCELLED');
```

Also update the idempotency guard in `ShipmentCreatedConsumer` to check for active rows only (`status NOT IN ('FAILED', 'CANCELLED')`).

---

### #2 — Travel time formula missing ×3600

**Location:** §M5-D-004 (fast-path formula)

**Problem:** The formula reads:

```
Haversine distance × ROAD_FACTOR ÷ AVG_SPEED_KMPH → estimated travel seconds
```

Dimensional analysis: `km × 1.4 ÷ 25 km/h = hours`, not seconds. The ×3600 conversion factor is absent. If the implementation follows the doc literally it produces values in hours interpreted as seconds — feasibility checks would accept every insertion as trivially feasible.

**Fix:** Update the formula in the doc and in `CronFeasibilityServiceImpl`:

```
travel_seconds = (haversine_km × ROAD_FACTOR / AVG_SPEED_KMPH) × 3600
```

Add a unit test that asserts a 5 km trip at 25 km/h × 1.4 road factor = 1008 seconds (not 0.28).

---

### #3 — Post-cron deferred retry has no cron to check against

**Location:** §6.3 (van handoff), §3 (DeferredRetryJob sequence diagram)

**Problem:** `da_cron_assignment` has `UNIQUE(da_id, operating_date)` — one cron record per DA per day. After `VAN_HANDOFF_COMPLETED`, that record has `status = COMPLETED`. When `DeferredRetryJob` fires and calls `assignPickup` for a deferred order, `CronFeasibilityService` will read a completed cron record. Sequence diagram 3 says "next cron cycle feasibility checked" but no second cron is ever scheduled or defined.

**Fix options (choose one):**

- **Option A:** After cron handoff, orders deferred with `reason = CRON_INFEASIBLE` or `CRON_LOCKED` skip the cron constraint entirely and are assigned with only the hub-return soft constraint (same as delivery DAs). Document this explicitly in §6.3.
- **Option B:** Allow multiple cron records per DA per day (drop or relax the unique constraint, add a `cron_sequence INT` column). This requires M6 to emit multiple `cron.scheduled` events per DA.

Option A is simpler and consistent with the stated v1 scope. If chosen, `DeferredRetryService` must detect that the associated cron is already COMPLETED before calling `CronFeasibilityService`.

---

### #4 — `van-handoff` API accepts one scan; event has `parcelCount`

**Location:** §6.3 (van handoff API), §16.2 (`VAN_HANDOFF_COMPLETED` event)

**Problem:** `POST /dispatch/da/{id}/tasks/{id}/van-handoff` body takes a single `parcel_scan` string. But `VAN_HANDOFF_COMPLETED` carries `parcelCount: 5`. A DA with 5 pickups hands off 5 barcoded parcels. The current API cannot model multi-parcel handoff: either the DA calls the endpoint 5 times (but `task_id` refers to a single task, not the cron meeting) or it's called once and `parcelCount` is inferred from completed `DispatchQueue` rows (making `parcelScan` in the event ambiguous — which scan?).

**Fix:** Change the API body to accept an array:

```json
{
  "parcel_scans": ["BLR-...", "BLR-...", "BLR-..."],
  "van_id": "...",
  "timestamp": "..."
}
```

`parcelCount` in the event is then `parcel_scans.length`. M5 validates that each scan matches a COMPLETED `DispatchQueue` row for this DA.

---

## Architecture Violation

### #5 — OSRM imports an internal class from `grid`

**Location:** §17.3 (OSRM reuse)

**Problem:** The design says:

> "The bean is in `com.oneday.grid.service.OsrmMatrixServiceImpl` — M5 declares it as a dependency via the `grid` module."

`CLAUDE.md` is explicit: *"Cross-module imports: only import another module's public service interface, never its internal classes."* `OsrmMatrixServiceImpl` is a package-private implementation class, not an interface.

**Fix:** M3 must expose a public interface in `com.oneday.grid.service` (e.g., `OsrmRoutingPort`) with a point-to-point route method:

```java
public interface OsrmRoutingPort {
    long routeDurationSeconds(List<LatLon> waypoints);
}
```

M5 wires `OsrmRoutingPort` only. The impl stays internal to the `grid` module.

---

## Missing Design Elements

### #6 — `CronMonitor` job is undefined

**Location:** M5-DA-STATUS-MACHINE.md (transitions), §17.1 (package layout)

**Problem:** Two transitions have no implementation path:

- `IDLE → CRON_LOCKED`: triggered by "AbsentDaDetectionJob/cron monitor: `scheduled_meeting_time − now() ≤ CRON_FREEZE_MINUTES`". No such check exists in `AbsentDaDetectionJob` as designed, and no `CronMonitorJob` is in the package layout.
- `CRON_LOCKED → AT_CRON`: triggered when "DA's GPS arrives within radius of `cron_vertex_id`". No service performs this GPS-vs-vertex proximity check.

**Fix:** Decide and document:

- Add a cron-lock check to `AbsentDaDetectionJob` (already runs every 5 min — acceptable lag for a 30-min freeze window) or create a dedicated `CronMonitorJob`.
- Add a proximity check to `DaStatusService.updateGps`: when DA status is `CRON_LOCKED` and GPS is within, say, 200m of `cron_vertex_id`, transition to `AT_CRON`.

Add both to the package layout in §17.1 and to the implementation plan.

---

### #7 — DA identity not validated against JWT

**Location:** §15 (all DA-facing endpoints)

**Problem:** Endpoints are `POST /dispatch/da/{da_id}/tasks/{task_id}/...`. The design specifies JWT auth with DA role but never requires M5 to verify that `{da_id}` in the URL matches the authenticated user's ID. Any DA with a valid JWT can submit GPS or task actions for any other DA's tasks.

**Fix:** Add to `DaDispatchController`:

```java
@PreAuthorize("hasRole('DELIVERY_ASSOCIATE') and authentication.name == #daId.toString()")
```

Or extract it into a `@DaOwnershipCheck` annotation. Document this check explicitly in §15.

---

### #8 — `QUEUE_REORDERED` partition key is undefined

**Location:** §16.2 (`DaEventProducer`), PR #9

**Problem:** PR #9 says `DaEventProducer` sets partition key to `shipment_id`. `QUEUE_REORDERED` is a DA-level event — `shipmentId` in `DaEventBase` would be null. A null Kafka partition key routes to a random partition, breaking any consumer that relies on ordering guarantees.

**Fix:** Define the partition key strategy per event type:
- Shipment-scoped events (`PICKUP_ASSIGNED`, `DROP_COMPLETED`, etc.): key = `shipmentId`
- DA-scoped events (`QUEUE_REORDERED`, `DA_ABSENT`, `CRON_MISSED`): key = `daId`

Document this in §16.2 and implement the conditional logic in `DaEventProducer`.

---

### #9 — `updated_at` trigger on `da_status` is mentioned but not defined

**Location:** PR #2 reviewer checklist

**Problem:** The PR #2 checklist says "verify `updated_at` trigger is present" on `da_status`, but no `CREATE TRIGGER` statement appears anywhere in the schema definitions or migration notes.

**Fix:** Either add the trigger to the migration:

```sql
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER da_status_updated_at
BEFORE UPDATE ON da_status
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

Or explicitly state that `updated_at` is managed at the application layer in `DaStatusServiceImpl` and remove the reviewer checklist item.

---

## Schema Issues

### #10 — `pickup_lat/pickup_lon` column names are wrong for delivery tasks

**Location:** `dispatch_queue` §14.1, `deferred_dispatch` §14.4

**Problem:** Both tables use `pickup_lat` / `pickup_lon` for both PICKUP and DELIVERY tasks. For a DELIVERY task these hold the customer's delivery address, not a pickup address. Misleading names cause bugs (e.g., filtering `WHERE task_type='DELIVERY' AND pickup_lat IS NULL`).

**Fix:** Rename to `task_lat` / `task_lon` before migrations are written. Much cheaper to fix now than after data exists.

---

### #11 — Missing index on `da_status` for city/shift queries

**Location:** `da_status` schema §14.3

**Problem:** `DaStatusService.findByCityIdAndShiftDateAndStatusIn` (used in shift load and absent detection for all DAs in a city) has no supporting index. With 200 DAs across 5 cities, a full table scan occurs on every absent detection job run (every 5 min).

**Fix:** Add to PR #2 migrations:

```sql
CREATE INDEX idx_da_status_city_shift ON da_status (city_id, shift_date, status);
```

---

### #12 — `cityId` is `String` in `DaEventBase` but `UUID` in all DB tables

**Location:** §16.2 (`DaEventBase`), §14 (all tables)

**Problem:** `DaEventBase.cityId` is typed `String`. Event payload examples show `"cityId": "BLR"` (a short string code). All five DB tables store `city_id UUID`. This dual representation means either city identifiers are not UUIDs (they are codes like "BLR") — which is undocumented — or there is a type mismatch between the event schema and the data model.

**Fix:** Explicitly document that `cityId` in Kafka events is a short city code (`BLR`, `DEL`, etc.) and is not the same as `city_id UUID` in the DB. Add a utility method or `CityCode` enum for the mapping. Cross-reference this in §14 (data model) and §16 (Kafka contracts).

---

## Minor Issues

### #13 — IN_PROGRESS task recovery after pod restart is unspecified

**Location:** §M5-D-005 (in-memory state)

**Problem:** §M5-D-005 says queues are rebuilt from DB on restart. If a DA had a task in `IN_PROGRESS` at crash time, `expected_eta` may be stale (in the past). §8.4 uses `expected_eta` as `T_current` for in-progress tasks — a past ETA makes all subsequent feasibility checks trivially pass (DA is "already done"), leading to over-assignment.

**Fix:** In `ShiftLoadJob` restart recovery: for any `IN_PROGRESS` task where `expected_eta < now()`, set `expected_eta = now()` in the recovered in-memory `DaQueue`. This is conservative and correct.

---

### #14 — Cross-territory search has a minor load-imbalance race

**Location:** §10.3 (cross-territory eligible DA selection)

**Problem:** The per-DA lock is acquired after the adjacent DA is selected (cheapest-insertion search happens before lock). If two overloaded-tile orders arrive simultaneously, both evaluate the same adjacent DA map at the same queue_depth snapshot and both select DA X. Both then acquire DA X's lock sequentially and both insert. DA Y remains idle. This is not a correctness bug (both insertions pass feasibility under the lock), but it can leave adjacent capacity unused.

**Note:** Acceptable for v1. Document in E10/E11 edge case table.

---

### #15 — Q-M4-9 is misaddressed

**Location:** M5-DEPENDENCY-QUESTIONS.md

**Problem:** Q-M4-9 (`cron.scheduled` event ownership) is addressed to "M4/M6 joint." The question is purely about M6's nightly replan output — M4 has no role in answering it. Sending it to M4 will cause it to be deprioritised.

**Fix:** Re-address Q-M4-9 to the M6 team only. Rename it Q-M6-1 to make the owner clear.

---

## Items That Are Fine (non-obvious, verified correct)

- **E10 concurrent same-DA inserts (§18):** Correctly handled by the per-DA `ReentrantLock` acquired before feasibility check — second order sees first order already in the queue.
- **`queue_depth` comparison for N-DAs per tile:** §17.2 explicitly uses the in-memory `DaLiveStatus` map (not the DB) for the hot path, so the 2-minute flush lag does not affect DA selection accuracy.
- **`dispatch_queue` append-only semantics:** Rows are never deleted; status column updates are acceptable — the `DA_ASSIGNMENT_AUDIT` table provides the immutable decision log.
- **OSRM circuit breaker fallback (§18 E9):** Fast-path continues with `ROAD_FACTOR × 1.2` buffer. Correctly handles OSRM downtime without blocking assignment.

---

*Review version: 1.0 — covers M5 design doc v1.1 dated 2026-05-19.*
