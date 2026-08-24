# Design: Order→Shipment abstraction + Mid-day DA reassignment (with custody transfer)

> **Status:** design-for-review (no code yet). Two independent features.
> **Scope decisions (confirmed):** (1) *every* booking becomes an Order; (2) stranded in-hand parcels move by **field DA→DA handoff**; (3) territory redistribution **auto-applies**, station manager is notified and can override.
> Companion docs: `ESCALATION-MATRIX.md`, `BUILD-ASSESSMENT.md` (same folder).

---

## Context — why we're doing this

**Feature 1 (simple):** The platform's atomic unit is `Shipment` = one parcel, *everywhere* — backend, dispatch/driver queue, and all six consoles. A merchant who places one **bulk order** (say 10 parcels) is shown 10 unrelated shipments. Today's only "bulk" mechanism is a **transient `cart`** that fans out to N independent shipments at checkout and then **is deleted** — the shipments carry no back-reference to each other (`CartServiceImpl.checkout` deletes each `cart_item` after booking). There is no `order_id` column, no order ref, no order entity. The one order-ish field, `B2bBookingRequest.purchaseOrderRef`, is accepted over the wire and **silently dropped** (never persisted). We want a durable **Order → N Shipments** grouping surfaced everywhere, with the driver app grouping parcels **by (order, location)**.

**Feature 2 (complex):** Most operational escalations — DA no-show, DA leaves mid-shift, van/bike breakdown — reduce to one problem: **a DA becomes unavailable mid-shift and their territory + parcels must move to the remaining DAs.** The detection signal already exists (`AbsentDaDetectionJob` → `DA_ABSENT`) but **nobody acts on it**. There is no neighbor-based territory redistribution and **no mechanism to transfer parcels already in a DA's physical custody**. On a one-day SLA there is no slack to absorb this silently.

---

# FEATURE 1 — Order → Shipment (1:N)

## 1.1 Core model change

Introduce a durable parent `parcel_order` that survives checkout; every `Shipment` points back to it by bare UUID (cross-module convention — no cross-module FKs).

**New (orders module, M4):**
- Table `parcel_order` + entity `ParcelOrder` — `id`, `order_ref` (unique), `customer_type` (B2B/B2C/C2C), `b2b_account_id` (nullable), `booked_by_user_id`, `status`, aggregate totals (`total_paise`, `parcel_count`), `purchase_order_ref` (finally give `B2bBookingRequest.purchaseOrderRef` a home), timestamps. New Flyway `V4_40+`.
- **Order ref scheme + counter**, mirroring the proven `ShipmentRefCounter` (`SELECT FOR UPDATE` per `(city, date)`): `1DD-ORD-{CITY}-{YYYYMMDD}-{NNNNN}`. New `OrderRefService` + `OrderRefCounter` (clone of `ShipmentRefServiceImpl` / `ShipmentRefCounter`).
- `shipments` table + `Shipment.java`: add nullable `order_id` UUID + index (`order_id`, `order_id,state`). JSONB not needed.

**Uniform rule (confirmed):** *every* booking creates or attaches an Order.
- Single B2C/C2C/B2B booking → creates an Order with `parcel_count = 1`, stamps `order_id` on the one shipment.
- Cart checkout / bulk upload → creates **one** Order, stamps every fanned-out shipment with the same `order_id`.

This makes "everything is an Order" true across the whole stack — consoles and the driver app never branch on "grouped vs bare."

## 1.2 Booking wiring (insertion points)

| Path | File | Change |
|---|---|---|
| B2C single | `orders/.../service/impl/BookingServiceImpl.java` (~L256–294) | create Order (count 1) → set `order_id` on the `new Shipment()` |
| B2B single | `orders/.../service/impl/B2bBookingServiceImpl.java` (L215–260) | same; also persist `purchaseOrderRef` onto the Order |
| Cart/bulk checkout | `orders/.../service/impl/CartServiceImpl.java` (L156–232) | **create one Order before the fan-out loop**, pass `orderId` into each `bookingService.bookSettled(...)` / `b2bBookingService.book(...)`; keep per-item idempotency key `cart-{itemId}`; Order captures the single cart Razorpay total |

The Order is the natural home for the **single-cart-Razorpay-capture** already done for B2C carts (`CartServiceImpl` charges once for the whole cart) — payment becomes an order-level fact, which it always physically was.

## 1.3 Surfacing the abstraction (read side — "one order, click to expand N shipments")

The abstraction is **derived by grouping on `order_id`** — no schema change beyond the column. Add order grouping to the existing read models rather than new tables:

- **Business/admin console** — `AdminOrdersController` + `AdminOrderQueryServiceImpl` / `AdminOrderSummaryServiceImpl`. New `GET /api/v1/orders` returning `OrderSummaryResponse` (order_ref, parcel_count, roll-up status, total, pickup/drop summary) with a nested/expandable `GET /api/v1/orders/{orderRef}` → the N `ShipmentSummaryResponse`. Card design: order header row (ref, count, aggregate SLA colour = worst-of-children) that expands to per-parcel rows.
- **Customer "my shipments"** — `MyShipmentsController` `GET /shipments/mine` → add an order-grouped variant (`GET /orders/mine`); `MyShipmentSummaryResponse` gains `orderRef`.
- **Driver app / dispatch queue** — the important one. Group by **(order_id, location)**, not just order:
  - `dispatch_queue` gets a nullable `order_id` (denormalized from shipment at assignment time so the DA read model needn't cross into orders).
  - `DaTaskServiceImpl.listTasks` / `DaTaskView` / `DaDispatchController GET /dispatch/da/{daId}/tasks` return **stops**: one card per `(order_id, pickup-or-drop location)`. A 10-parcel order with 4 sharing a drop address → the delivery DA sees **7 stops** (one 4-parcel card + six singletons); a bulk pickup at one sender → **1 pickup card** exposing N parcels on tap. Lifecycle actions stay per-`taskId` under the group.

**Roll-up status rule:** order status = a deterministic reduction over child shipment states (e.g. worst-of for SLA colour, "partially delivered" when children diverge). Define this reduction once (e.g. `OrderStatusReducer`) and reuse in every console.

## 1.4 Aggregate/roll-up "gotchas" to decide in review
- **Partial cancellation / partial RTO** — one parcel of an order RTOs while 9 deliver. Order status must express "partially delivered / mixed." Reducer handles it; UI shows a mixed badge.
- **Split across legs** — parcels of one order can ride different flights / different delivery DAs. Order is a *booking* grouping, **not** a routing unit — never assume one order = one van/flight/DA.
- **Billing** — B2B invoice/wallet debit is already per-shipment; Order is a display+grouping layer over it, not a new billing unit in v1 (call out explicitly so finance isn't surprised).

---

# FEATURE 2 — Mid-day DA reassignment + custody transfer

**Problem restated:** a DA becomes unavailable mid-shift (no-show, silent/heartbeat-lapse, van/bike breakdown, quits early). Two things must happen, fast, on a 1-day clock:
1. **Territory** — the DA's hexes get redistributed to *neighboring* DAs, **evenly**, minimal change to everyone else, DAs stay geographically local.
2. **Custody** — parcels the DA held move to whoever now covers that area: (a) **not-yet-picked-up** pickups and (b) **not-yet-delivered** parcels queued, both just re-queue; (c) **parcels already in the DA's hands** need a real physical **DA→DA field handoff**.

## 2.1 What already exists (reuse, don't rebuild)

| Capability | Where | Reuse as |
|---|---|---|
| Absence detection | `dispatch/.../batch/AbsentDaDetectionJob` → `DA_ABSENT` (daId+cityId) | the **trigger** (today consumed by no one for action) |
| DA availability enum | `DaStatusEnum` = OFFLINE/IDLE/IN_PROGRESS/CRON_LOCKED/AT_CRON/**ABSENT** | status source |
| H3 neighbor primitive | `H3Core.gridDisk(idx,1)` in `GridReplanServiceImpl.buildGeometricAdjacency` | find neighbor hexes |
| Adjacency graph (travel-time) | `OsrmMatrixService` / `h3_hex_travel_time` (V3_4) | preferred neighbor weighting |
| Append-only intraday override | `ProposalServiceImpl.requestIntradayReassignment` (contiguity-validated, `INTRADAY_OVERRIDE`, supersede-not-delete) | **the write path** for territory moves |
| Contiguity guard | `ContiguityValidator.isConnected` | "keep DAs local" enforcement |
| Even-load balancer | `BalancedBfsAssignmentServiceImpl` | reference for the even split |
| DA→DA neighbor stub | `dispatch/.../service/AdjacentDaProvider` (only a **no-op** bean today) | natural home for neighbor discovery |
| Custody scans (append-only) | M8 `scan_ledger` via `ScanLedgerPort`; van types VAN_LOAD/VAN_TO_DA/DA_TO_VAN/VAN_UNLOAD | extend with a **DA_TO_DA** type |
| Task re-queue precedent | `DispatchServiceImpl.cancelTask` + `assignDelivery`; `da_id` is `updatable=false` → move = cancel+recreate | queued-task reassignment |
| Recovery van | `routing/.../RecoveryService` + `POST /routing/vans/{vanId}/recovery` | van-breakdown fallback |

**The design is ~70% orchestration over existing parts, not new subsystems.** The genuinely new pieces are: a grid-side `DA_ABSENT` consumer, the even-split orchestrator, the intraday **dispatch territory-refresh seam**, a **DA_TO_DA** scan type, and the DA→DA handoff reconciliation flow.

## 2.2 Reassignment flow (auto-apply, SM notified)

```
DA_ABSENT (or manual "DA unavailable" from SM console / breakdown report)
        │
        ▼
[grid] DaReassignmentService  (NEW — grid-side consumer of DA_ABSENT)
  1. hexes = DaHexAssignmentRepository.findByDaIdAndValidDate(daId, today) filtered APPROVED
  2. for each released hex → neighborDAs = gridDisk(hex,1) → owning APPROVED DAs
        (weight by OSRM travel-time; skip absent/offline/cron-locked DAs)
  3. EVEN SPLIT: assign each released hex to the least-loaded contiguous neighbor
        (BalancedBfs-style greedy; ContiguityValidator keeps both territories connected;
         goal = minimal disturbance to standing territories, load spread not dumped on one)
  4. write ONE INTRADAY_OVERRIDE proposal (append-only) and AUTO-APPROVE it
        (reuse requestIntradayReassignment + approveIntradayReassignment;
         supersedes only the affected DAs' APPROVED rows — nightly plan untouched)
  5. emit DA_TERRITORY_REASSIGNED (NEW event) + notify SM (override-able)
        │
        ▼
[dispatch] intraday territory-refresh (NEW seam)
  consume DA_TERRITORY_REASSIGNED → DaStatusService.setTerritory(newDaId, tiles)
  (today setTerritory is only called by ShiftLoadJob — no intraday path exists)
        │
        ▼
[dispatch] custody + queue move (see 2.3)
```

**Edge — no available neighbor** (all neighbors absent/cron-locked, or the absent DA covered a whole city corner): fall back to **cross-territory spill** (implement the `AdjacentDaProvider` no-op) and, failing that, escalate P1 to STATION_MANAGER + hold affected pickups (matches escalation-matrix A1/A2). Never silently drop coverage.

## 2.3 Custody transfer — the three buckets

The absent DA's parcels split by physical state (all derivable from `dispatch_queue.status` + `picked_up` + scan trail):

**Bucket A — queued pickups (not yet collected).** `dispatch_queue` rows `task_type=PICKUP, status=QUEUED`. → cancel old rows, recreate under the new territory-owner (the cancel+recreate pattern already used because `da_id` is immutable). No physical custody involved. **Automatic.**

**Bucket B — queued deliveries (parcel at hub / not in this DA's hand).** `task_type=DELIVERY, status=QUEUED`. → same cancel+recreate; the parcel is physically at the hub or on a van, so only the *task* moves. Reuse `assignDelivery`. **Automatic.**

**Bucket C — parcels IN the DA's physical hands** (`picked_up=true` for a pickup en route to hub, or `status=IN_PROGRESS` delivery already collected from hub). **This is the hard one — no mechanism exists today** (`cancelTask` only *logs* the hand-back). Confirmed model = **field DA→DA handoff**:
  1. Reassignment engine identifies the inheriting DA for each in-hand parcel's destination territory.
  2. Open a **custody-transfer task**: inheriting DA is routed to the stranded DA's current GPS location (rendezvous). This is the "jaha se wo packet lele" step.
  3. Physical exchange scanned as a **new `DA_TO_DA` scan** (NEW `ScanLedgerPort` scan type — append-only, so the transfer is *new rows*, never a mutation of the derived custodian).
  4. Reconcile the exchange like `HandoffService` does for van↔DA (expected set vs scanned set → MISSING/EXTRA buckets, `HANDOFF_DISCREPANCY` on mismatch).
  5. Re-create the onward dispatch task under the inheriting DA; the parcel's SLA leg continues.
  - **Van breakdown with a full load / no nearby DA** → fall back to `RecoveryService` (recovery van collects, returns to hub, re-sorts) — slower, flagged as at-risk. (We chose DA→DA as the *primary* model; recovery-van is the documented fallback when field handoff is infeasible.)

**Why append-only matters here:** custody is *derived from the last scan*, never stored. So "transfer" = write a `DA_TO_DA` scan row; the forensic ledger stays intact and every console's custody read (`ShipmentScanTrailPort`) updates for free.

## 2.4 New/changed pieces summary (Feature 2)
- **grid:** `DaReassignmentService` + `DA_ABSENT` consumer (new); reuse `ProposalService` override path + `gridDisk` + `ContiguityValidator`; new `DA_TERRITORY_REASSIGNED` event (common).
- **dispatch:** intraday `setTerritory` refresh consumer (new seam); implement `AdjacentDaProvider` (kill the no-op); custody-transfer task type + rendezvous routing; bucket A/B cancel+recreate orchestration.
- **common/barcode (M8):** add `DA_TO_DA` scan type; M8 stores it — ledger-only, no schema change (`scan_type` is free-text `varchar(24)`).
- **routing (M6):** reuse `HandoffService` reconciliation shape for the DA→DA exchange; `RecoveryService` as fallback.
- **exceptions (M11):** open a case on reassignment for audit/SM visibility + a new `REASSIGN_PICKUP` action to complement the existing `REASSIGN_DELIVERY` (which today is a shipment re-dispatch, not a custody move).

---

# What we're missing operationally (brainstorm — beyond the two asks)

Grounded in `ESCALATION-MATRIX.md` (which already scores these). The two features above close A1/A2 (no-show/absent) but leave these open:

1. **No-show that never comes online.** `AbsentDaDetectionJob` skips `OFFLINE` DAs, so a DA who *never* pings is never flagged. Absence detection must also run a **roster-vs-online reconciliation at shift start** (expected DAs from `DaDirectoryPort` vs who actually pinged). Without it, Feature 2's trigger never fires for the most common real case. **Highest-value gap.**
2. **Cron-cutoff miss has no emitter.** `CRON_MISSED` case type exists but nothing detects it — the single most SLA-critical middle-mile breach on a 1-day clock is dead. M5 must emit it.
3. **Flight miss** — M7 re-bags on `FLIGHT_REASSIGNED`, but no exception case, and M9 producer isn't live.
4. **No escalation engine** — M11 is a manual queue: no severity field, no SLA timers, no auto-escalation SUPERVISOR→STATION_MANAGER→ADMIN. An open case can sit forever.
5. **No notifications** — platform-wide gap. Every reassignment/handoff/escalation above is silent unless a human is watching a screen. A DA→DA rendezvous is useless if neither DA is pinged.
6. **Auto-RTO / DA accountability** — max-attempt only *labels* UNDELIVERABLE; `da_attributable` is dead in practice → no no-show/breakdown penalty record.
7. **COD custody on reassignment** — when in-hand parcels move DA→DA, **COD cash liability must move too** (or stay with a reconciliation hold). Not modeled; a real cash-variance risk the moment Feature 2 ships.
8. **Phantom metrics to fix before any CEO demo** — `on-time %` is always ~0 (`expected_eta` never written); control-tower dates default to UTC not IST (wrong board for the first 5.5h of every IST day).

**Recommendation on sequencing:** Feature 1 is genuinely simple and low-risk — do it first as one clean slice. Feature 2 is worth building **only alongside items 1 and 5** (roster-vs-online no-show detection + notifications) — otherwise the reassignment engine has an unreliable trigger and a silent output, and won't hold up in the room. Treat 2, 3, 4 (cron/flight emitters + escalation timers) as the parallel track the escalation-matrix doc already lays out.

---

# Suggested build order (for the review)

**Feature 1 (independent, ship first):**
1. `parcel_order` table + entity + `OrderRefService`/counter; `shipments.order_id` column (Flyway V4_40+).
2. Wire the three booking paths (B2C, B2B, cart checkout) to create/attach an Order.
3. `dispatch_queue.order_id` denormalization at assignment; order-grouped `DaTaskView` stops (by order+location).
4. Console/customer read models: `GET /orders`, `GET /orders/{ref}`, `orders/mine`; `OrderStatusReducer`.

**Feature 2 (behind items 1+5 of the gap list):**
5. Roster-vs-online no-show detection (fix the trigger) — dispatch.
6. `DaReassignmentService` even-split orchestrator + auto-apply override (grid) + `DA_TERRITORY_REASSIGNED`.
7. Intraday dispatch territory-refresh seam + `AdjacentDaProvider` impl; bucket A/B re-queue.
8. `DA_TO_DA` scan type + DA→DA field-handoff task + reconciliation; recovery-van fallback; COD liability move.

---

# Verification (when built)

- **Feature 1:** unit tests on `OrderRefService` (counter concurrency, format `1DD-ORD-…`), booking-service tests asserting `order_id` stamped on every path, cart-checkout test asserting one Order over N shipments. Integration: place a bulk cart order → `GET /orders/{ref}` returns N children; hit `GET /dispatch/da/{id}/tasks` and assert a multi-parcel pickup collapses to one stop and a 4-shared-address order collapses to the right stop count. Run against local Postgres per `CLAUDE.md` (`mvn test -pl orders,dispatch`).
- **Feature 2:** unit test the even-split (released hexes distributed to least-loaded contiguous neighbors; contiguity preserved). Integration/sim: mark a DA `ABSENT` mid-shift with queued + in-hand parcels → assert (a) territory override applied and superseded (nightly rows intact), (b) dispatch tile→DA index refreshed, (c) queued tasks re-created under new DAs, (d) a `DA_TO_DA` handoff task opens and reconciles, (e) scan trail shows the DA_TO_DA row and custody reads flip. The M6 simulation harness (PR#8, planned) is the natural E2E home. Build/run must use **JDK 21** (`JAVA_HOME=/opt/homebrew/opt/openjdk@21`).

# Open questions for the review
- **Order status reducer semantics** — exact roll-up for mixed states (partial delivery/RTO/cancel). Proposed: worst-of for SLA colour, explicit "MIXED/PARTIAL" for terminal divergence.
- **DA→DA rendezvous routing** — who computes the meet point / is it just the stranded DA's live GPS? Any max-detour cap before we fall back to recovery-van?
- **COD liability transfer** — move cash liability to the inheriting DA, or freeze on the stranded DA with a recon hold? (Finance decision.)
- **Reassignment audit** — one M11 case per absence event (recommended) vs silent grid-only override.
