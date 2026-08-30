# Delivery Outcomes (PR #171) — review follow-ups

Marked from Sid's PR comments (2026-08-29). **FIX-1…FIX-5 implemented 2026-08-30** together with the
CodeRabbit triage (see bottom). Remaining unchecked items are deferred follow-ups / verification gaps.

## Config / naming

- [x] **FIX-1 — `landing-base-url` → env-only + rename.** Today it defaults to
  `https://godspeed-customer.vercel.app` baked in `OrdersDeliveryProperties`. Remove the prod-URL
  default; source per-environment (env var, like Razorpay/R2). Rename to something intuitive —
  e.g. property `orders.delivery.customer-landing-base-url`, env `CUSTOMER_LANDING_BASE_URL`
  (the receiver accept/reject landing page).

## ETD accuracy

- [x] **FIX-2 — ETD must include flight air-time.** `DeliveryConfirmationServiceImpl.computeEta()`
  bases the projection on `Instant.now()` (departure moment) + hub-processing + last-mile, treating
  the flight as instantaneous (~2h optimistic on DEL↔BOM). Base it on the **assigned flight's
  scheduled arrival** (flight record / `EtaPort`) instead of `now()`. **Decided: fix.**
- [ ] **NOTE — global per-parcel ETA service (larger follow-up).** `computeEta` is a local coarse
  projection with flat 120+60 min windows. Lift ETA to a platform-level per-parcel service that
  tracking (LiveTrack `EtaPort`), SLA (M10), and this confirmation all read from one source of truth,
  fed by real per-lane durations. FIX-2 is the first step; this is the parent.

## Receiver reject semantics (reverses two original plan decisions)

- [x] **FIX-3 — count a proactive reject as a delivery attempt.** Original plan treated a reject as a
  courtesy reschedule (M11 `attempt_no` untouched). **Sid's decision: count it.** A reject should
  register an attempt with reason `CUSTOMER_REJECTED` (new reason), be monitored, share the same
  cap (no more than 3 attempts/delivery), and roll into **auto-RTO (ops-confirmed)** at the cap —
  same machinery as a door-failure. Needs: emit/record an M11 attempt on reject + wire into the
  `attempt_no` cap + a `CUSTOMER_REJECTED` reason.
- [x] **FIX-4 — honor a reject even when the parcel is already out for last-mile.** Today
  `deferDeliveryForRetry` no-ops if an active DELIVERY task exists (QUEUED **or** IN_PROGRESS), so a
  reject that lands after the DA has collected (IN_PROGRESS) just waits for a doomed door-attempt.
  Instead: on such a reject, proactively **cancel today's delivery task + spawn `RETURN_TO_HUB`
  carry-back + reschedule** next-day. Saves the DA a wasted trip and us the failed attempt. (Last-mile
  "out" = task IN_PROGRESS via `drop-collected`; a still-QUEUED task can just be suppressed with no
  carry-back.)

## Expiry / TTL

- [x] **FIX-5 — parcel-state check before expiring/auto-actioning + shorten TTL.** The 12h
  (`confirmation-ttl-minutes=720`) can outlive the delivery itself. Before expiring (and before any
  future auto-action), check the parcel's current M4 state — don't act on a confirmation whose parcel
  is already DELIVERED / terminal. Consider **12h → 6h**. Later stage: **auto-reject at expiry**
  (today silence = accept); deferred, needs product sign-off.

## RTO automation seams (fully-automatic booted intercity)

RTO business logic is complete + e2e-verified; these are what's needed for a **hands-off intercity**
run (same-city already collapses to delivery and is complete):

- [ ] **SEAM-1 — return child re-entry.** The child is a new ref `<ref>_R` but the physical parcel
  came back under the original barcode. Either the dock operator receives `<ref>_R` via the single
  `POST /hub/{hubId}/receive` (works today — ArrivalMode derived from `AT_ORIGIN_HUB`), or activate
  the dormant `AT_ORIGIN_HUB` auto-trigger (`hub ShipmentStateConsumer`, `autoStartup=false`, TODO:
  resolve hub UUID → `HubReceivingService`). Decide operator-scan vs auto-trigger.
- [ ] **SEAM-2 — reverse-lane flight seeding.** A dest→origin outbound flight-bag needs an active
  `ConsolidatorLaneRate` + flights on the reverse lane, or a booted flight assignment can't complete.
  Seed/verify reverse lanes (airline).

## Verification gaps (not defects)

- [ ] **VERIFY-1 — boot the delivery-outcomes flow in a HUB_RETURN city.** The e2e ran on
  DEL↔BOM (VAN_MEETING). The new carry-back / reject / RTO-child features rest on shared
  meeting-mode-agnostic logic + unit tests for HUB_RETURN, but were never booted in a HUB_RETURN
  city (`city_fleet_config` = HUB_RETURN + routing `@Primary CityMeetingModeAdapter`). Should work
  by construction; confirm with a booted run. Not a known defect.

## Answered, no change needed (recorded for the triage)

- **`REASSIGN_DELIVERY` is NOT the midday DA-absence flood-fill** — unrelated, no shared code.
  Absence = territory-level flood-fill split + DA↔DA custody (`CUSTODY_COLLECT`), triggered by
  marking a DA absent. `REASSIGN_DELIVERY` = single-parcel M11 exception action (new DA, same day),
  no custody exchange. Complementary, not duplicate.

- `REASSIGN_DELIVERY` = manual M11 console action (new DA, same day); `RESCHEDULE_DELIVERY` = park
  next-day, same plan. Only caller is the exceptions console resolve endpoint.
- Hub console has **one** receive button; `HUB_RETURN_IN` is a driver-app custody scan, not a second
  console button.
- Van vs HUB_RETURN cities share the whole spine; only the last-mile collect node differs
  (van loop vs hub visit). Redelivery + RTO logic is identical.

## CodeRabbit triage (2026-08-30)

Fixed:
- **DaTaskServiceImpl** — reject a delivery reattempt after its carry-back already COMPLETED (409);
  only cancel QUEUED/IN_PROGRESS carry-backs.
- **MerchantAnalyticsServiceImpl** — classify `HELD_AT_HUB` as an RTO state (was reported inTransit).
- **ReturnServiceImpl** — lock the original (`findByIdWithLock`) before the idempotency check so
  concurrent RTO_INITIATED calls serialize instead of racing to a uniqueness error.
- **DeliveryConfirmationServiceImpl** — a failed notification send now rolls back the PENDING row
  (REQUIRES_NEW) instead of leaving a live prompt that suppresses later retries; trigger swallows.
- **E2E-REPORT.md** — added `text` language to the fenced block (MD040).
- **oneday-web** — station filter/label consistency ("Return to sender"), removed dead `RTO_IN_TRANSIT`
  from station buckets/format + `packages/api` ShipmentState, added `HELD_AT_HUB`.

Skipped (with reason):
- **ShipmentState — historical `RTO_IN_TRANSIT` reads** (Major): verified **0 rows** carry it in
  shipments / shipment_state_history on the dev DB (no producer ever emitted it). Re-adding the
  deleted state would contradict the feature. Not applicable.
- **REQUIRES_NEW for AFTER_COMMIT listeners**: already fixed in `e2f3e89` (CodeRabbit confirmed).
- **oneday-web page.tsx "run typecheck/build"**: process reminder — ran `pnpm -r typecheck` (exit 0).
