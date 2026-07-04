# M5 Demo — Gap Tickets

Two concrete tickets closing the M5 demo gaps found during the demoability audit
(see `M5-UI-SCENARIO-COVERAGE.md`). Each is the "real" fix, not a UI fake.

- **Ticket A — Reassign-on-absent** (PR #5 narrative). Independent; can land now.
- **Ticket B — OTP pickup step** (PR #11 narrative). **Gated** on the M4-booking → M5
  integration the other session is doing (needs real shipmentIds, not synthetic).

---

## Ticket A — Reassign an absent DA's queued parcels

### Problem
The PR #5 story is "flag Anjali absent → **her parcels get reassigned, not stranded**."
That reassignment **does not exist**. Both `AbsentDaDetectionJob.sweep()` and the demo's
`DispatchDemoService.markAbsent(...)` only call `daStatusService.updateStatus(ABSENT)` +
`daEventProducer.emitDaAbsent(...)`. The absent DA's QUEUED tasks stay on her card; marking
absent only stops *new* pickups from routing to her. So in a live demo the parcels just sit there.

This is an **implementation gap**, not only a UI gap.

### Good news — the assigner already excludes absent DAs
`DispatchServiceImpl.assign()` filters its roster with `isAssignable(...)`:

```java
List<UUID> roster = daStatusService.dasForTile(tileId);
List<UUID> assignable = roster.stream().filter(this::isAssignable).toList();
```

So **re-running assignment for a task just works** — it routes to another feasible DA in the
tile, or defers if none fits. We only need to (a) drain the absent DA's QUEUED tasks and
(b) re-feed each through the assigner with deferral reason `DA_ABSENT`.

### Change
1. **New public method on `DispatchService`** (real M5, not demo):
   ```java
   /** Re-run assignment for every QUEUED task on a now-unavailable DA. Returns count reassigned+deferred. */
   int reassignQueuedTasksFrom(UUID daId, DeferReason deferReason);
   ```
   Implement in `DispatchServiceImpl`:
   - Under `daStatusService.withDaLock(daId, …)`: read the DA's QUEUED rows for today
     (`sortedActive` filtered to `QUEUED`), snapshot each task's `{shipmentId, cityId,
     taskType, taskLat, taskLon, tileId}`, **remove them from her queue** (same delete +
     resequence path as `cancelTask`), and emit one `QUEUE_REORDERED` for `daId`.
   - **Outside her lock** (avoid cross-DA deadlock), for each snapshot call the existing
     `assign(Request(...), true)`. The target DA's lock is taken inside `assign`. A task that
     fits lands on another DA (emits `PICKUP_ASSIGNED`); a task that doesn't defers — pass
     `deferReason = DA_ABSENT` so the deferral row/reason reflects the cause.
   - **Leave IN_PROGRESS tasks alone** — that DA is already en route; reassigning mid-pickup is
     an M11/exceptions concern, not the assigner's.
   - Record `da_assignment_audit` per re-decision (the existing `assign` path already does).

2. **Wire it into both callers:**
   - `AbsentDaDetectionJob.sweep()` — after `updateStatus(ABSENT)` + `emitDaAbsent`, call
     `dispatchService.reassignQueuedTasksFrom(daId, DeferReason.DA_ABSENT)`.
   - `DispatchDemoService.markAbsent(...)` — same call after the status flip.

3. **`DeferReason.DA_ABSENT`** already exists; no enum/migration change.

### Acceptance criteria
- Load shift, assign pickups so Anjali has ≥3 QUEUED. Click her "absent" button.
  → Her card empties of QUEUED tasks; her status badge = ABSENT.
  → Those tasks reappear on other DAs' cards **or** in the Deferred panel as `DA_ABSENT`
    (when no other DA in the tile is feasible).
  → An IN_PROGRESS task on Anjali (if any) stays put.
- Unit test: a DA with 3 QUEUED + 1 IN_PROGRESS, one other idle feasible DA in-tile →
  `reassignQueuedTasksFrom` moves the 3, leaves the 1, returns 3.
- No deadlock under the two-lock path (drain-then-assign ordering).

### Notes
- The deferred row carries no `paymentMode` (consistent with `reassignDeferred` today; COD
  prioritisation is a later concern).
- This also upgrades scenario **S4** from "stops taking new pickups" to the full PR #5 beat.

---

## Ticket B — OTP pickup confirmation step in the demo

### Problem
PR #11 ("Meena says 4821 → verified → PICKED_UP, SLA clock starts") has **no UI path**, and
can't work on the demo's synthetic shipmentIds: `OtpVerificationServiceImpl.verifyOtp` calls
`pickupOtpService.verify(shipmentId, otp)` against M4, which requires a real `Shipment` row +
a `PickupOtp` row. The current demo `workNext` skips OTP entirely
(QUEUED → en-route → van-handoff).

### Hard dependency (blocker)
**This ticket is gated on the M4-booking → M5 integration** (real shipmentIds flowing into
M5 dispatch). Until a pickup is a real M4 shipment, there is nothing to verify. Do Ticket B
**after** that wiring lands.

### Sub-problem: the demo needs the cleartext OTP
The pickup OTP is BCrypt-hashed (M4) and "dispatched to the sender by M4/notification — not
returned here" (`OtpVerificationServiceImpl.resendOtp` comment). There is no notification
service, so the demo can't know the code. Two options:

- **(B1, recommended) `!prod` cleartext peek in M4.** When `PickupOtpService` generates an OTP,
  also stash the cleartext in a `@Profile("!prod")` in-memory cache keyed by shipmentId, exposed
  via `String peekOtp(UUID shipmentId)` (or a demo endpoint). Demo-only; never under `prod`.
- (B2) Have the demo generate the OTP itself and verify with the same value — rejected: bypasses
  M4's real verify path, so it wouldn't actually exercise PR #11.

### Change
1. **M4:** add the `!prod` cleartext peek (B1) — cache-on-generate + `peekOtp(shipmentId)`.
2. **M5 demo service** (`DispatchDemoService`): inject `OtpVerificationService`; add
   ```java
   DispatchState verifyOtp(UUID cityId, LocalDate date, UUID daId, UUID taskId, String otp);
   ```
   delegating to `otpVerificationService.verifyOtp(daId, taskId, otp)` (which → PICKED_UP +
   `PICKUP_COMPLETED`). On `ResponseStatusException` (422 wrong/expired, 409 illegal state) catch
   and surface the message in the returned state / an error field — the wrong-OTP path is part of
   the demo.
3. **M5 demo controller:** `POST /api/demo/dispatch/verify-otp` (cityId, date, daId, taskId, otp);
   optional `GET /api/demo/dispatch/otp-peek?shipmentId=` (!prod) for the UI to reveal the code.
4. **Lifecycle ordering:** insert OTP between en-route and van-handoff. Pickup becomes:
   `QUEUED → markEnRoute (IN_PROGRESS) → verifyOtp (shipment PICKED_UP, PICKUP_COMPLETED) →
   recordVanHandoff (COMPLETED)`. Either gate `workNext`'s van-handoff on "shipment PICKED_UP",
   or (preferred for visibility) make OTP an explicit per-task action.
5. **UI (`DispatchView`):** on an IN_PROGRESS pickup task, show a "Verify OTP" affordance with an
   input. For demo convenience, a "reveal" link calls `otp-peek`. Correct OTP → row shows
   "✓ picked up"; wrong OTP → inline 422 error, task stays IN_PROGRESS. Optional "resend" (429 on
   limit).
6. **`TaskView`:** add a `pickedUp` flag (derive from shipment state) so the UI can render the beat.

### Acceptance criteria
- A real booked shipment assigned to a DA, advanced to en-route, shows "Verify OTP".
  - Correct OTP → task marked picked-up; `PICKUP_COMPLETED` emitted; M10 SLA leg starts.
  - Wrong OTP → 422 surfaced; task stays IN_PROGRESS; no state change.
  - Van-handoff is only allowed after PICKED_UP.
- `otp-peek` and the cleartext cache are unreachable under the `prod` profile.

### Edge cases (stretch, optional)
- "M4 down → retry then mark failed" (PR #11 tail): `verifyOtp` currently has no retry; a
  resilience pass (Ticket-14 territory) would add it. Out of scope for the demo step.

---

## Suggested order
1. Ticket A now (independent, closes the PR #5 beat + S4).
2. Other session: M4-booking → M5-dispatch wiring (unblocks S8/S14 + Ticket B).
3. Ticket B once real shipmentIds flow.
