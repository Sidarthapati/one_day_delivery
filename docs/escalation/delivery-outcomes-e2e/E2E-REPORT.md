# Delivery Outcomes — Local End-to-End Test Report

**Date:** 2026-08-28 · **Branch:** `feat/delivery-outcomes` · **Result:** ✅ all three workstreams verified live; 2 real bugs found + fixed.

## Environment (fully isolated throwaway)

| Piece | Value |
|-------|-------|
| Backend | the assembled `app` fat-jar, JDK 21, on **:8085** |
| Database | **fresh throwaway `oneday_e2e`** (Postgres 16, local) — all Flyway migrations ran from scratch, incl. the feature's `V4_45`/`V4_46`/`V4_47`/`V5_17` |
| Broker | local Docker RabbitMQ on an **isolated vhost `e2e`** (no cross-talk with any other instance) |
| Web | the customer app (`feat/delivery-outcomes`) pointed at :8085, for the landing-page screenshots |
| Auth | real M1 JWT (registered a user, promoted to ADMIN in the DB) — no dev auth shortcut exists |

**Method.** The full physical pipeline (first-mile DA → hub sort → flight → hub → last-mile) is not practically bootable locally (needs seeded flights/consolidator data + the clock-based flight jobs). So each feature's **own code paths were exercised for real over HTTP + the event bus + the DB**, and only the upstream *preconditions* those legs would produce were set directly (labelled "setup" below). Every feature transition — the carry-back spawn, the return mint, the receiver reject, the completion — ran through the real services, real RabbitMQ events, and the real state machine.

## Parcels booked (real bookings via the API)

Both are real Delhi→Mumbai PREPAID B2C bookings (mock Razorpay gateway, real HMAC signature verified), priced by the live pricing engine at ₹581.74.

- `1DD-DELHI-20260828-00001` — Riya → Arjun — **Workstream B** (receiver reject)
- `1DD-DELHI-20260828-00002` — Riya → Meera — **Workstream A + C** (carry-back + return)

---

## Workstream A — redelivery base (carry-back)

*Setup:* parcel #2 set to `DROP_ASSIGNED` (the point the inbound pipeline hands a parcel to last-mile) + a QUEUED `DELIVERY` task seeded for a registered DA.

| Step (real HTTP) | Result |
|---|---|
| `POST …/tasks/{id}/drop-collected` | task → `IN_PROGRESS`; shipment → `DROP_COLLECTED` (via the DA event → M4 state machine over RabbitMQ) |
| `POST …/tasks/{id}/failed` (in-hand) | **`RETURN_TO_HUB` carry-back task spawned** (QUEUED, picked_up=t); shipment → `DELIVERY_FAILED`; **M11 exception case opened** (attempt_no=1) |
| `POST …/tasks/{id}/returned-to-hub` | carry-back task → `COMPLETED`; `HUB_RETURN_IN` scan event emitted on the bus |

✅ An in-hand delivery failure produces a modelled way back to the hub, and completing it records the hub-return. *(The `HUB_RETURN_IN` **event** fires on completion; persisting it to the M8 `scan_ledger` needs a generated parcel label, which these teleported parcels don't have — a v1 seam, not a feature defect.)*

## Workstream C — the return framework (child `_R`)

Continued from parcel #2 (now `DELIVERY_FAILED` with an open M11 case).

| Step (real HTTP / event) | Result |
|---|---|
| `POST /api/v1/exceptions/{case}/resolve {action: INITIATE_RTO}` | M11 resolve → `RTO_INITIATED` event → `ReturnService.initiateReturn` |
| → return child minted | **`1DD-DELHI-20260828-00002_R`**, born `AT_ORIGIN_HUB` |
| → reversed geography | origin/dest **MUMBAI→DELHI** (was DELHI→MUMBAI); sender/receiver **swapped** (Meera↔Riya) |
| → priced + linked | reverse-lane price ₹581.74; `return_of_shipment_id` → original; original → `RTO_INITIATED`, `return_shipment_id` → child |
| deliver the child (`drop-completed`) | child → `DROPPED` → **`ReturnCompletionListener` drove the original → `RTO_COMPLETED`** |

✅ A return is a real child shipment flowing the normal pipeline backwards; when it's delivered the original closes as returned. The old `RTO_IN_TRANSIT` machinery is gone.

## Workstream B — receiver accept / reject (email + landing page)

*Setup:* parcel #1 set to `AT_AIRPORT`, then a **real `FlightEvent(DEPARTED)`** published to the flight exchange → the actual `FlightEventsConsumer` transitioned it to `DEPARTED`.

| Step | Result |
|---|---|
| flight departs | `DeliveryConfirmationTrigger` (AFTER_COMMIT) → **confirmation prompt created**: `PENDING`, ETD computed from flight timing = **today morning / SHIFT_1** |
| email "sent" | rendered to the `notification_log` outbox (dev `LoggingEmailSender`); raw token recovered from the link |
| `GET /public/v1/deliveries/{token}` (no auth) | returns parcel + ETD + `can_respond` — **landing page renders** (screenshots `b-01`) |
| receiver rejects, picks **tomorrow afternoon** | `POST …/reject {target_shift: SHIFT_2}` → confirmation → `REJECTED` (response_shift SHIFT_2) — page shows "Rescheduled" (`b-02`, `b-03`) |
| → `RECEIVER_REJECTED` event → dispatch | **delivery re-parked**: `deferred_dispatch` row `RECEIVER_REJECTED`, `operating_date 2026-08-29`, `target_shift SHIFT_2`, PENDING |
| M11 attempt count | **0 cases for parcel #1** — a proactive reject is a courtesy reschedule, **not** a failed attempt ✅ |

Screenshots: `b-01-pending.png`, `b-02-reject-picker.png`, `b-03-rescheduled.png`.

---

## 🐞 Two real bugs found (and fixed) — commit `e2f3e89`

Both are `@TransactionalEventListener(AFTER_COMMIT)` paths that do DB writes. After commit there is **no active transaction**, and a default-propagation `@Transactional` call binds to the already-committed transaction — so the work either throws (a locked read) or is **silently lost**. Unit tests mocked the state machine / repos, so neither surfaced until this live run.

1. **`ReturnCompletionListener`** — the locked read threw `Query requires transaction be in progress`, so a delivered return child never completed its original. (Caught as an error in the log.)
2. **`DeliveryConfirmationServiceImpl.promptOnDeparture`** — writes silently rolled back; the receiver confirmation row + notification **never persisted** (no error, misleading "sent" log). In production the receiver prompt would never have worked.

**Fix:** `@Transactional(propagation = REQUIRES_NEW)` on both, so the post-commit work runs in a fresh committing transaction. Re-ran live afterward: both now persist/complete correctly. Orders unit suite still green.

## Final DB state (proof)

```text
 shipment_ref               | state         | orig  | dest  | sender  | receiver | is_return_child | has_child
 1DD-DELHI-20260828-00001   | DEPARTED      | DELHI | MUMBAI| Riya    | Arjun    | f               | f
 1DD-DELHI-20260828-00002   | RTO_COMPLETED | DELHI | MUMBAI| Riya    | Meera    | f               | t
 1DD-DELHI-20260828-00002_R | DROPPED       | MUMBAI| DELHI | Meera   | Riya     | t               | f
```

- **B** `delivery_confirmation`: REJECTED, response_shift SHIFT_2 · `deferred_dispatch`: RECEIVER_REJECTED / 2026-08-29 / SHIFT_2 / PENDING
- **A** `dispatch_queue`: DELIVERY FAILED → RETURN_TO_HUB COMPLETED
- **C** `exception_case`: DELIVERY_FAILED → RTO/RETURNED · child DELIVERY COMPLETED · original RTO_COMPLETED

## Not booted (covered by tests instead)

The driver-app `RETURN_TO_HUB` card (React Native/Expo) was not booted here — it's covered by the driver PR's typecheck + jest bucket test. The `HUB_RETURN_IN` → `scan_ledger` persistence and a fully-automatic (no-teleport) intercity flow both require the flight/consolidator seeding already flagged as follow-ups in the PR.
