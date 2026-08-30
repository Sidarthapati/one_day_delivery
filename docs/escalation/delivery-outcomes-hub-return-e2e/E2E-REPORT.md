# Delivery Outcomes — HUB_RETURN End-to-End Test Report

**Date:** 2026-08-30 · **Branch:** `feat/delivery-outcomes` · **Result:** ✅ all three workstreams verified live in a **two-city HUB_RETURN** topology (no vans).

This closes **VERIFY-1** from `docs/escalation/delivery-outcomes-e2e/REVIEW-FOLLOWUPS.md` — the earlier
run booted DEL↔BOM as **VAN_MEETING**; this one boots **both cities as HUB_RETURN** (the DA collects
the parcel at the hub and walks it to the door; on failure walks it back), which is the mode the
receiver-reject + RTO features had only been unit-tested in.

## Environment (fully isolated throwaway)

| Piece | Value |
|-------|-------|
| Backend | assembled `app` fat-jar (commit `c8e1788`), JDK 21, on **:8087** |
| Database | **fresh throwaway `oneday_hr_e2e`** (Postgres 16, local) — all 152 Flyway migrations from scratch |
| Broker | local Docker RabbitMQ on an **isolated vhost `hr-e2e`** (no cross-talk with other instances) |
| Web | the customer app (`feat/delivery-outcomes`) on **:3000 → :8087**, for the landing-page screenshots |
| Auth | real M1 JWT (registered a user, promoted to ADMIN in the DB) — no dev auth shortcut exists |

**Both cities flipped to HUB_RETURN** via `PUT /routing/fleet/{cityId}` (`{"meeting_mode":"HUB_RETURN","hub_return_interval_minutes":60}`):

```text
 city_id (Delhi)  f47ac10b-…  | HUB_RETURN | 60
 city_id (Mumbai) 550e8400-…  | HUB_RETURN | 60
```

**Method.** The full physical pipeline (first-mile DA → hub sort → flight → hub → last-mile) is not
practically bootable locally (needs seeded flights/consolidator data + clock-based jobs). So each
feature's **own code paths were exercised for real over HTTP + the event bus + the DB**, and only the
upstream *preconditions* those legs would produce were teleported directly (labelled "setup"). Every
feature transition — the hub collect, the carry-back, the return mint, the receiver reject, the
completion — ran through the real services, real RabbitMQ events, and the real state machine, **with
both delivery cities in HUB_RETURN mode** so the mode-specific branches fired.

## Parcels booked (real bookings via the API)

Both are real Delhi→Mumbai PREPAID B2C bookings (mock Razorpay gateway, real HMAC signature verified),
priced by the live pricing engine.

- `1DD-DELHI-20260830-00001` — Riya → Arjun — **Workstream B** (receiver reject)
- `1DD-DELHI-20260830-00002` — Riya → Meera — **Workstream A + C** (carry-back + return)

---

## Workstream A — redelivery base (carry-back) — HUB_RETURN

*Setup:* parcel #2 teleported to `HUB_DELIVERY_ASSIGNED` (the Mumbai hub sorts it for last-mile in a
HUB_RETURN city) + a QUEUED `DELIVERY` task seeded on a registered **Mumbai** DA (task `city_id` = Mumbai).

| Step (real HTTP) | Result |
|---|---|
| `POST …/tasks/{id}/hub-collect` | task → `IN_PROGRESS`; shipment → **`COLLECTED_FROM_HUB`** (the HUB_RETURN collect state — **not** the van `DROP_COLLECTED`; the DA-event `DROP_COLLECTED` was re-mapped by `CityMeetingModePort` because Mumbai is HUB_RETURN). The `requireHubReturnCity` mode-guard **passed** (no 409). |
| `POST …/tasks/{id}/failed` (in-hand) | **`RETURN_TO_HUB` carry-back task spawned** (QUEUED, picked_up=t); shipment → `DELIVERY_FAILED`; **M11 case opened** (attempt_no=1) |
| `POST …/tasks/{id}/returned-to-hub` | carry-back → `COMPLETED`; `HUB_RETURN_IN` seam scan emitted |

✅ In a hub-return city, a hub-collected delivery that fails in hand still produces a modelled carry-back
to the hub. The carry-back/failure code is mode-agnostic; only the collect state differs from a van city.

## Workstream C — the return framework (child `_R`) — HUB_RETURN both legs

Continued from parcel #2 (now `DELIVERY_FAILED`, open M11 case). Ops triggers the return.

| Step (real HTTP / event) | Result |
|---|---|
| `POST /api/v1/exceptions/{case}/resolve {action: INITIATE_RTO}` | M11 resolve → `RTO_INITIATED` event → `ReturnService.initiateReturn` |
| → return child minted | **`1DD-DELHI-20260830-00002_R`**, born `AT_ORIGIN_HUB` |
| → reversed geography | origin/dest **MUMBAI→DELHI** (was DELHI→MUMBAI); sender/receiver **swapped** (Meera↔Riya) — the reverse lane is **also HUB_RETURN** (Delhi) |
| → priced + linked | reverse-lane priced; `return_of_shipment_id` → original; original → `RTO_INITIATED`, `return_shipment_id` → child |
| deliver the child in Delhi (HUB_RETURN) | `hub-collect` → **`COLLECTED_FROM_HUB`** → `drop-completed` → `DROPPED` → **`ReturnCompletionListener` drove the original → `RTO_COMPLETED`** |

✅ The return child flows the normal pipeline backwards **as another HUB_RETURN delivery** to the sender;
when it's delivered the original closes as returned. The `ReturnCompletionListener` AFTER_COMMIT write
(the `REQUIRES_NEW` bug fixed in `e2f3e89`) completed correctly.

**Parcel #2 audit trail (HUB_RETURN last-mile vocabulary, not van):**

```text
 → BOOKED
BOOKED               → HUB_DELIVERY_ASSIGNED
HUB_DELIVERY_ASSIGNED → COLLECTED_FROM_HUB
COLLECTED_FROM_HUB   → DELIVERY_FAILED
DELIVERY_FAILED      → RTO_INITIATED
RTO_INITIATED        → RTO_COMPLETED
```

## Workstream B — receiver accept / reject (email + landing page) — HUB_RETURN

*Setup:* parcel #1 teleported to `AT_AIRPORT`, then a **real `FlightEvent(DEPARTED)`** published to
`oneday.flight.events` → the actual `FlightEventsConsumer` transitioned it to `DEPARTED`.

| Step | Result |
|---|---|
| flight departs | `DeliveryConfirmationTrigger` (AFTER_COMMIT) → **confirmation created**: `PENDING`, ETD computed from the parcel's promised ETA (**FIX-2**) = **NEXT_DAY / SHIFT_2** ("tomorrow afternoon") |
| email "sent" | rendered to `notification_log` (dev sender); raw token recovered from the `/d/{token}` link |
| `GET /public/v1/deliveries/{token}` (no auth) | returns parcel + ETD + `can_respond=true` — **landing page renders** (`hr-b-01`) |
| receiver rejects, picks **tomorrow afternoon** | UI → `POST …/reject {target_shift: SHIFT_2}` → confirmation → `REJECTED` (response_shift SHIFT_2) — page shows "Rescheduled" (`hr-b-02`, `hr-b-03`) |
| → `RECEIVER_REJECTED` → dispatch | **re-parked**: `deferred_dispatch` `RECEIVER_REJECTED`, `operating_date 2026-08-31`, `target_shift SHIFT_2`, PENDING |
| → `RECEIVER_REJECTED` → exceptions | **M11 attempt recorded (FIX-3):** `exception_case` `DELIVERY_FAILED` / **`CUSTOMER_REJECTED`** / attempt_no=1 / da_attributable=f / OPEN |

Screenshots: `hr-b-01-pending.png`, `hr-b-02-reject-picker.png`, `hr-b-03-rescheduled.png`.

> **Behaviour change vs the earlier van run:** the DEL↔BOM report noted "0 M11 cases for the rejected
> parcel — a proactive reject is not a failed attempt." That was **before FIX-3**. With FIX-3 in this
> build, a proactive reject now **does** open a `CUSTOMER_REJECTED` M11 attempt (still `da_attributable=f`)
> so it shares the 3-attempt cap and rolls into auto-RTO — verified here (attempt_no=1).

---

## Final DB state (proof)

```text
 shipment_ref               | state         | orig   | dest   | sender  | receiver | is_child | has_child
 1DD-DELHI-20260830-00001   | DEPARTED      | DELHI  | MUMBAI | Riya    | Arjun    | f        | f
 1DD-DELHI-20260830-00002   | RTO_COMPLETED | DELHI  | MUMBAI | Riya    | Meera    | f        | t
 1DD-DELHI-20260830-00002_R | DROPPED       | MUMBAI | DELHI  | Meera   | Riya     | t        | f
```

- **A** `dispatch_queue` (parcel #2): DELIVERY `FAILED` → RETURN_TO_HUB `COMPLETED`
- **C** child `1DD-…-00002_R`: DELIVERY `COMPLETED`; original `RTO_COMPLETED`
- **B** `delivery_confirmation`: REJECTED / SHIFT_2 · `deferred_dispatch`: RECEIVER_REJECTED / 2026-08-31 / SHIFT_2 / PENDING · `exception_case`: CUSTOMER_REJECTED / attempt_no 1
- **Event bus health:** every DLQ on the exercised path (`flight`, `da`, `delivery.confirmations`,
  `exceptions`, `shipments`, `scan`) = **0 messages** — nothing dead-lettered, no consumer errors.

## Conclusion

The delivery-outcomes epic (receiver confirmation, redelivery carry-back, and the return/RTO framework)
works **identically in a HUB_RETURN city** — the only difference is the last-mile collect node
(`COLLECTED_FROM_HUB` in place of the van `DROP_COLLECTED`), which the `CityMeetingModePort` mapping
handles transparently. VERIFY-1 is satisfied by a booted run, not just unit tests.

## Not booted (covered by tests / prior run)

The physical inbound legs (first-mile, hub sort, flight, hub) were teleported to their preconditions, as
in the DEL↔BOM run — the feature paths themselves all ran live. The driver-app `RETURN_TO_HUB` card
(React Native) is covered by the driver PR's jest bucket test.
