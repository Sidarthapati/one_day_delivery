# M5 — Dispatch: As-Built Note

| Field | Value |
|-------|-------|
| Module | M5 — `dispatch` |
| Status | As-built, verified against `main` 2026-10-20 (Plan A · Day 2) |
| Scope | DA assignment, cron-feasibility gate, queue sequencing on real GPS, cross-territory spill |

Short "know every piece" note: what the code actually does today, as distinct from the design doc
(`M5-DISPATCH-DESIGN.md`). Transport is RabbitMQ, not Kafka (see that doc's banner).

## The two entry points

1. **Assignment** — `DispatchServiceImpl.assignPickup / assignDelivery` (called by `ShipmentEventsConsumer`
   off `oneday.shipments.events`, and directly by the demo). Places one task on one DA.
2. **Sequencing** — `QueueReorderService.reorder(daId, date)`. Re-scores a DA's QUEUED tail. Runs under the
   per-DA lock, triggered on every queue-changing event (insert / drop / task terminal) and by the slow
   safety tick `QueueReorderJob` (default 300s).

## Assignment flow (`DispatchServiceImpl.assign`)

```
idempotency (active task for this shipment+type? → return it)
  → resolve tile (given originTile/destTile, else GridServiceabilityAdapter)
  → roster = DAs registered on that tile (in-memory, da_status)
  → assignable = roster filtered by isAssignable (online, not cron-frozen, …)
      empty? → defer: CRON_LOCKED (a frozen DA exists) or NO_DA_AVAILABLE
  → primary = assignable DA with the min queue depth
  → attemptOnDa(primary): cron-feasibility gate (see below)
      infeasible at every insertion position? → tryCrossTerritory (spill)
          still no? → defer: CRON_INFEASIBLE
```
Every outcome is recorded to `da_assignment_audit` (append-only) + a metric. Deferred tasks land in
`deferred_dispatch` (PENDING) and are retried by `DeferredRetryJob` / `reassignDeferred`.

## Cron-feasibility gate (the hard constraint)

`CronFeasibilityService.checkFeasibility` (assignment) and `cronSlackSeconds` (reorder) answer: *can the
DA still service this set of stops and reach the cron/van-meeting vertex by cutoff, with a
`cronSafetyMarginMinutes` (default 10) buffer?* It is **cheapest-insertion**: tries every position, returns
the min-detour feasible one. Travel is a Haversine fast-path with an OSRM override at the borderline
(`OsrmRoutingPort`); OSRM-open falls back to conservative Haversine. A cron is "active" unless its status is
COMPLETED / CANCELLED / MISSED. No cron → tasks simply append.

## Sequencing on real GPS (`QueueReorderService`)

- **Score** = `distanceWeight·proximity + ageWeight·aging` (defaults 0.6 / 0.4), both in [0,1]. Past
  `ageSaturationMinutes` (120) a task hits the **starvation floor** (`1 + ageMinutes`) so it outranks any
  fresh task regardless of distance — the anti-starvation guarantee. The IN_PROGRESS prefix is never moved.
- **Anchor** (the reference point for both proximity and cron feasibility):
  - DA has an IN_PROGRESS task → that task's location + its expected ETA.
  - else → the DA's **live GPS**: `DaStatusService.getLiveStatus(daId).lat/lon`, which `updateGps` keeps
    live on every `POST /dispatch/da/{id}/gps` ping (also appended to the `da_gps_ping` trail).
- **Cron-aware prefix**: under an active cron the priority order is applied only as far as it stays
  cron-feasible. Tasks that don't fit are kept on the DA but demoted to the tail and flagged
  `beyond_cron` ("after van meeting"); they auto-promote once reachable again. As cutoff nears, the
  feasible prefix shrinks to empty → the cron effectively gets 100% priority.

## Verified behaviours & known edges

- **Real-GPS sequencing works for the normal case.** A loaded DA always has an in-memory `DaLiveStatus`;
  each ping updates its lat/lon, so reorder and the cron gate anchor on the real position.
  (Test: `reorderAnchorsOnLiveGpsPosition`.)
- **No GPS yet → cron gate off, by design.** A loaded DA that hasn't pinged has `getLiveStatus() != null`
  but null lat/lon → `anchorLoc == null` → the cron gate is skipped and the tail reorders age-only
  (`QueueReorderService:112`). We can't assess feasibility without a position. (Test:
  `noGpsFixSkipsCronGateAndReordersByAgeOnly`.)
- **reorder only ever runs for a loaded DA** — `QueueReorderJob` ticks `loadedDaIds()` only; `assign` /
  `cancelTask` hold the DA lock on a loaded DA. So a DA that fell out of the roster is not resequenced
  until reloaded, even though its live GPS is still on the `da_gps_ping` trail (which `LiveDaPositionAdapter`
  exposes to M10/tracking). Harmless today; revisit if eviction-under-load becomes real.
- **GPS is tracking-only, not a gate.** The 200m arrival geofence was removed (Jul-20);
  CRON_LOCKED → AT_CRON is a manual "Mark arrived".
- **Scale hotspot**: the cheapest-insertion / feasible-prefix loops are O(n²) in a DA's queue depth —
  the thing to watch on the stress day (Plan A Day 11).

## Test coverage (dispatch suite, mock-based)

- Assignment — `DispatchServiceImplTest`: least-loaded pick, cheapest-insertion + row-bump, defer
  (no-DA / cron-locked / cron-infeasible), completed-cron appends, cancel resequence, deferred retry,
  concurrent-serialize.
- Feasibility — `CronFeasibilityServiceImplTest`: travel formula, empty-queue, cheapest-insertion,
  all-positions-infeasible, OSRM borderline (×3), in-progress slack, stateless concurrency.
- Spill — `DispatchServiceImplTest`: cross-territory assign to sparse neighbour; graceful defer when
  load-score fails / origin not overloaded.
- Sequencing — `QueueReorderServiceTest`: aging overtakes proximity, in-progress pinned, beyond-cron
  demote / all-beyond / promote-back, **GPS anchor**, **no-fix gate-skip**.
