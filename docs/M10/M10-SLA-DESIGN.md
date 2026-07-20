# M10 — SLA Monitoring & Escalation · Design (v1.0)

> Status: **built** (branch `f-m10-design`). Consumes every module's lifecycle events; owns its own
> tables; escalates to Station Manager / Admin. Self-contained — no other module's logic changes.
> Requirements: `docs/godspeed/M10-SLA.md`, `docs/MODULES.md §M10`, PRD §12.4 + FR-11.

## Table of contents
- **Part 0 — Physical reality** (glossary, one-parcel walkthrough)
- **Part I — What & boundaries** (scope, the leg model, key decisions)
- **Part II — The engine** (clock, legs, projection, escalation, sweeper, dashboard)
- **Part III — Integration & governance** (contracts, RACI, open questions, testing)

---

## Part 0 — Physical reality

**The promise M10 guards.** Godspeed promises **24h** to the market and runs to **16h** internally.
Reliability *is* the product: M10 watches every parcel against a per-leg time budget, colours each leg
GREEN / AMBER / RED, and escalates a parcel to a human *before* the customer promise is at risk.

**Glossary.**
- **Leg** — one hop of the journey (pickup, origin-hub, origin-airport, air, dest-airport, dest-hub,
  last-mile). Enum `SlaLegType`.
- **Budget** — the minutes a leg *should* take. Config `sla.legs`. The seven budgets sum to **13.5h**,
  leaving a **2.5h internal cushion** under the 16h target. A further **8h** (16h→24h) is the
  customer-facing engineered buffer.
- **Projection** — a deterministic roll-forward of when the parcel will finish (Part II).
- **Breach** — the 16h internal target actually passes, or a hard failure (RTO, pickup/delivery fail).

**One parcel (DEL→BOM).** Booked 08:00 → the 16h clock starts, internal target **00:00**, public
promise 08:00+24h. First mile budgeted 3h. The pickup runs 2h late; at 13:00 the parcel reaches the
origin hub. M10 closes FIRST_MILE (overran → that leg shows **AMBER**), opens ORIGIN_HUB, and projects
the finish: 13:00 + remaining budgets (60+120+150+90+60+150 = 630m) = **23:30** — still before 00:00,
so the parcel is **AMBER, not RED**. If instead the delay pushed the projection past 00:00, the open
leg flips **RED**, an append-only `sla_escalation` is written, and `SLA_ESCALATION_RAISED` fires to the
Mumbai... no — the **Delhi** station manager (custody is still origin at the hub). The parcel is
escalated well before the 24h public promise is ever in danger.

---

## Part I — What & boundaries

### 1. What M10 does
Consumes lifecycle events from M4/M5/M6/M7/M9/M11, maintains a per-parcel **leg ledger**, runs a
**buffer-aware projection** each time new information lands (and on a periodic sweep), derives
GREEN/AMBER/RED per leg + a shipment rollup, **escalates** RED/breach to the right role, keeps an
**append-only** escalation + action audit, and serves a **control-tower API** to Station Manager
(own city) and Admin (all cities), including a measured **pass-rate** metric.

### 2. Scope

**In:** per-leg SLA state; buffer-aware projection; RED/breach escalation + notification event;
append-only audit; control-tower/red-queue/pass-rate/detail API with acknowledge/act; the SLA clock
and per-leg deadlines as M10's own source of truth.

**Out (v1):** automated mitigation / rebooking (M9/M11 own that — M10 flags, humans act); live plane
position (M9 is a stub → the air leg uses scheduled times / budget); a GPS breadcrumb (live stores
overwrite in place); the web control-tower page (separate `oneday-web` repo, follow-on); back-filling
the cross-module `slaDeadline` into other modules (deferred — M10-D-007).

**Gaps this module closes:** there was **no SLA anywhere**. Every upstream module already tagged events
"→ M10"; this is the consumer that finally acts on them.

### 3. The leg model (reconciled to `ShipmentState`)
`ShipmentState` has **30** states (roles: **12**; the requirement doc's 31/10 are stale). The seven
legs map to state ranges; a state that isn't in a shipment's plan (e.g. the air legs of a SAME_CITY
parcel) simply never opens.

| Leg | Live during states | Budget |
|---|---|---|
| FIRST_MILE | BOOKED, PICKUP_ASSIGNED, PICKED_UP, HANDED_TO_PICKUP_VAN, RETURNED_TO_HUB, AWAITING_SELF_DROP | 180m |
| ORIGIN_HUB | AT_ORIGIN_HUB, ORIGIN_HUB_PROCESSING, IN_TAKEOFF_BAG | 60m |
| ORIGIN_AIRPORT | DISPATCHED_TO_AIRPORT, AT_AIRPORT | 120m |
| AIR | DEPARTED | 150m |
| DEST_AIRPORT | LANDED, DISPATCHED_TO_HUB | 90m |
| DEST_HUB | AT_DEST_HUB, DEST_HUB_PROCESSING, AWAITING_HUB_COLLECT | 60m |
| LAST_MILE | HANDED_TO_DROP_VAN, DROP_ASSIGNED, DROP_COLLECTED, HUB_DELIVERY_ASSIGNED, COLLECTED_FROM_HUB | 150m |

Terminal success: `DROPPED`, `HUB_COLLECTED`. Exceptions (breach, stay open): `PICKUP_FAILED`,
`DELIVERY_FAILED`, `RTO_INITIATED`, `RTO_IN_TRANSIT`. Close: `RTO_COMPLETED`, `CANCELLED`.
Variants: **SAME_CITY** = FIRST_MILE → ORIGIN_HUB → LAST_MILE (air legs + second hub collapse);
**HUB_RETURN** / **HUB_COLLECT** reuse the same legs via their alternate states.

### 4. Key design decisions
- **M10-D-001** — GREEN / AMBER / RED per leg. *(DECIDED)*
- **M10-D-002** — RED requires a supervisor/station-manager *action*, not just a notice. *(DECIDED)*
- **M10-D-003** — hub→airport handover minute threshold. *(OPEN — Q-L3)*
- **M10-D-004** — all SLA + escalation actions are append-only audited. *(DECIDED)*
- **M10-D-005** — **buffer-aware projection**: AMBER = a leg overran its own budget; RED = the
  *projected* end-to-end finish crosses the 16h internal target. *(DECIDED — resolves Q-S1)*
- **M10-D-006** — **clock start = order-placed**: both the 16h and 24h clocks anchor at `BOOKED`.
  *(DECIDED — resolves Q-S2)*
- **M10-D-007** — **self-contained**: M10 owns its tables and reads other modules only via the event
  bus; it emits escalation/breach events but changes no other module's logic. *(DECIDED)*

---

## Part II — The engine

### 5. Clock & anchors (M10-D-006)
On `ShipmentCreatedEvent`: `booked_at = occurredAt`, `internal_target_at = booked_at + 16h`,
`public_promise_at = booked_at + 24h`; `eta_promised` (M4's customer ETA) is kept for reference. A
leg plan is materialised from `DeliveryType`; FIRST_MILE is live from booking.

### 6. Leg lifecycle (the backbone)
`ShipmentStateChangedEvent` drives boundaries: on a transition into a state whose leg differs from the
current one, M10 **completes** every upstream leg (`completed_at`) and **opens** the new leg
(`started_at`, `deadline_at = started_at + budget`). "Only-advance" semantics make it idempotent
(re-delivery of an event never regresses a timestamp). Terminal states close the SLA; exceptions mark
it breached but keep it open until RTO/cancel.

### 7. Projection (M10-D-005)
```
projected_finish = expected_end_of_current_leg + Σ(budget of every downstream leg not yet started)
RED    if projected_finish > internal_target_at
AMBER  if a leg overran its budget but projected_finish ≤ internal_target_at
GREEN  otherwise
```
`expected_end_of_current_leg` = the leg's enrichment estimate (`projected_end_at`) if present, else
`started_at + budget`, floored at `now` (an overrunning leg finishes no earlier than now — the
least-alarming assumption). A completed leg is GREEN if it beat its deadline, AMBER if it overran (it
ate cushion but is done). Deterministic and explainable — no learned ETA. Implementation:
`ProjectionCalculator` (pure, unit-tested).

**Enrichment (parcel-keyed only).** `ParcelSortedForDeliveryEvent.slaDeadline` → LAST_MILE deadline;
`FlightReassignedEvent.newCutoff` + its `parcelIds` → ORIGIN_AIRPORT deadline; `LoopOverflowEvent`
(parcel + deadline) → tightens the open leg. Van/flight/bag-level signals with no parcel id
(`VAN_RUNNING_LATE`, `FLIGHT_TIME_CHANGED`, `BAG_RESCHEDULED`) are **logged, not attributed** in v1 —
attributing them needs a van/bag→parcel map M10 (self-contained) doesn't hold. *(honest v1 limit)*

### 8. The sweeper
Time is a signal events can't send: a leg can blow its deadline while nothing happens. `SlaSweeper`
(`@Scheduled`, 60s) re-evaluates every open shipment so a silent overrun still flips AMBER→RED and
escalates. Idempotent; cheap at pilot volume.

### 9. Escalation (M10-D-002/004)
On entering **RED** → one append-only `sla_escalation` at level **STATION_MANAGER**, city = the
parcel's **custody city** (destination once on the dest legs, else origin), plus a
`SLA_ESCALATION_RAISED` event and a WARN log. On **BREACHED** → level **ADMIN** + a `SLA_BREACHED`
event (→ M11 reason code). Idempotent per `(shipment, leg, colour)` so re-evaluation never
double-fires. A manager **acknowledges** / **acts** via the API, appending `sla_action` rows (the
escalation row itself is never mutated). The notification service resolves the on-duty person from the
event's `level` + `city` (contract only; no SMS provider yet, mirroring `LoggingOtpSender`).

### 10. Pass-rate metric
`passed = closed − breached` over a window (city-scoped). The 99% gate (Annexure D/L) is
`GET /metrics/pass-rate`.

---

## Part III — Integration & governance

### 11. Contracts

**Consumed** (exact concrete payload classes — the header `__TypeId__` must resolve, so consumers take
the sealed base / rich type the producer stamps):

| Exchange | Payloads → effect |
|---|---|
| `oneday.shipments.events` | `ShipmentCreatedEvent` (open) · `ShipmentStateChangedEvent` (backbone) · `ShipmentCancelledEvent` (close) |
| `oneday.hub.events` | `ParcelSortedForDeliveryEvent` (last-mile deadline) · `DestSortCompleteEvent` (re-eval) |
| `oneday.cron.events` | `LoopOverflowEvent` (tighten open leg) |
| `oneday.flight.events` | `FlightReassignedEvent` (airport deadline; dormant until M9) |
| `oneday.da.events` | `DaLifecycleEvent` — PICKUP_COMPLETED / CRON_MISSED re-eval |
| `oneday.exceptions.events` | `ExceptionsEvent` (re-eval; dormant until M11) |

Grid alerts (NO_DA / TILE_OVERLOAD) are a **deferred** input — their payloads live in the grid module,
not `common`, so a self-contained M10 does not couple to them in v1.

**Produced** on `oneday.sla.events` (new): `SlaEscalationRaisedEvent` (`SLA_ESCALATION_RAISED`),
`SlaBreachedEvent` (`SLA_BREACHED`). Plus `NotificationEventType.SLA_ESCALATION` for the notification
service. All additive to `common`.

**REST** (`/api/v1/sla`, STATION_MANAGER own-city / ADMIN all-city; actions need `sla:red:action`):
`GET /control-tower`, `GET /shipments/{ref}`, `GET /red-queue`, `GET /metrics/pass-rate`,
`POST /escalations/{id}/ack`, `POST /escalations/{id}/act`.

**Data model** (Flyway `sla/db/migration/sla/V10_1`): `sla_shipment` + `sla_leg` (mutable),
`sla_escalation` + `sla_action` (append-only).

### 12. Cross-module RACI
M10 is **R** for SLA state, projection, escalation raise, pass-rate. Station Manager / Supervisor are
**R** for acting on RED (city). Admin / control tower **A** for breaches and lane-level RED. M9/M11
are **R** for the actual mitigation (rebook / RTO); M10 only surfaces.

### 13. Open questions
- **Q-S1** buffer allocation — **resolved** by M10-D-005 (global buffer-aware; per-leg budgets = AMBER
  trigger, projection vs 16h = RED).
- **Q-S2** clock start — **resolved** by M10-D-006 (order-placed).
- **Q-S3** 3rd-party light-node last-mile without own-scan coverage — **open**.
- **M10-D-003** hub→airport handover minute threshold — **open** (Q-L3).
- **Van/flight/bag → parcel attribution** — open; needs a shared map or a `common` payload carrying
  parcel ids (would also let grid alerts and van-late enrich per parcel). Deferred with the back-fill.

### 14. Testing
Unit: `ProjectionCalculatorTest` (buffer-aware table incl. AMBER-not-RED, cushion→RED, historical
AMBER, enrichment override), `SlaLegCatalogTest` (plans, state→leg, classification). Manual E2E
(Part of the implementation plan's Verify): book → drive states + push `VAN_RUNNING_LATE` / force a
projection past 16h → observe AMBER→RED, an `sla_escalation` row, and `oneday.sla.events`; hit the
dashboard as ADMIN vs STATION_MANAGER.
