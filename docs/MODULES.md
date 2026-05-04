# Module Breakdown — One-Day Intercity Delivery Platform

> **How to read this document**
> Each module is a logical and architectural unit that can be designed, built, and tested by one sub-team independently. Dependencies between modules are explicit (inputs come from other modules' outputs via well-defined contracts — APIs, events, or shared data stores). Modules are ordered roughly bottom-up: foundational ones first, operational ones last.

---

## Module Index

| # | Module | One-liner |
|---|--------|-----------|
| M1 | [Auth & Role Management](#m1-auth--role-management) | Identity, permissions, and session for all actor types |
| M2 | [Pricing & Costing Engine](#m2-pricing--costing-engine) | Quote and charge computation for B2B and B2C shipments |
| M3 | [Serviceability & Grid Management](#m3-serviceability--grid-management) | Rectangular tile grid over urban pincodes; dynamic rebalancing |
| M4 | [Order Booking & Shipment Lifecycle](#m4-order-booking--shipment-lifecycle) | Customer-facing booking, ETA promise, and canonical order state machine |
| M5 | [DA Assignment & Dispatch](#m5-da-assignment--dispatch) | Real-time delivery associate queue, assignment, and cron-meeting constraint |
| M6 | [Van Routing & Scheduling](#m6-van-routing--scheduling) | Nightly route plan for hub consolidation vans on the grid graph |
| M7 | [Hub Operations & Sortation](#m7-hub-operations--sortation) | Inbound sort, stand assignment, bag creation, and system manifests |
| M8 | [Barcode, Label & Scanning](#m8-barcode-label--scanning) | Unique parcel identity, label generation, and scan-event ledger |
| M9 | [Airline & Flight Integration](#m9-airline--flight-integration) | Flight schedule, hub-level assignment, handover tracking, and GHA APIs |
| M10 | [SLA Monitoring & Escalation](#m10-sla-monitoring--escalation) | Per-leg SLA computation, red-state detection, and supervisor escalation |
| M11 | [Exception Handling, Call Center & RTO](#m11-exception-handling-call-center--rto) | Failure capture, call center routing, rescheduling, penalties, and RTO |

---

## M1 — Auth & Role Management

### Business Logic / Requirements
- Maintain the following actor roles with distinct permission scopes:

| Role | Scope |
|------|-------|
| **Admin** | All cities; full override on grids, routes, config, and users |
| **Station Manager** | Single city; grid and route override for that city; SLA red actions |
| **Supervisor** | Assigned legs/stations; SLA red-state action (may be same person as station manager or separate) |
| **Hub Operator** | Hub scan, stand assignment, bag operations |
| **Delivery Associate (DA)** | Own queue; barcode attach; scan events |
| **Van Driver** | Own route; stop confirmation |
| **Hub–Airport Cron Driver** | Cron run confirmation |
| **Call Center Agent** | Failure capture; reschedule input |
| **B2B Business User** | Contract pricing portal; shipment creation; reporting API |
| **B2C End Customer** | Shipment booking; tracking |
| **Airline/GHA (read-only)** | Manifest viewing; handover acknowledgement |

- All role changes and permission grants must be **append-only audit-logged**.
- Session tokens must support **city-scoped claims** (a station manager's token carries their city; actions outside that city are rejected at service layer).
- Support **API keys** for B2B users (machine-to-machine) and **short-lived JWTs** for human actors.

### Inputs
- Actor registration data (name, role, assigned city/station, credentials)
- Admin-initiated role grant or revoke actions

### Outputs
- Authenticated session token (JWT) with embedded role and city claims
- Permission check API (`can_actor_X_do_Y_in_city_Z → bool`) consumed by all other modules
- Audit log entries for all auth and permission events

---

## M2 — Pricing & Costing Engine

### Business Logic / Requirements
- Compute a **quoted price** for a shipment before booking, and a **final charge** after actual weight/dimensions are confirmed.
- Pricing dimensions (minimum):
  1. **Customer type** — B2B (account-specific contract rate) vs B2C (standard rate card)
  2. **Origin city** and **destination city** (city-pair pricing)
  3. **Volumetric (dimensional) weight** — `max(actual_weight, L×W×H / divisor)` where divisor is contractually set
- B2B accounts have **account-level rate tables** (negotiated; stored per account); B2C uses a published rate card.
- A **costing model** runs in parallel: it ingests DA capacity, working hours, van utilisation, and hub cost data to produce **per-parcel cost floor** — this is used internally by scheduling algorithms to evaluate feasibility but is **not exposed to customers**.
- Pricing and costing data must be **versioned**: historical orders always re-compute against the rate card active at booking time.

### Inputs
| Input | Source |
|-------|--------|
| Customer type (B2B account ID or B2C flag) | M4 booking request / B2B API |
| Origin city, destination city | M4 booking request |
| Package dimensions (L, W, H) and actual weight | M4 booking request |
| B2B contract rate table | Admin-configured in this module |
| B2C rate card | Admin-configured in this module |
| DA capacity and hours data (for costing model) | HR/ops configuration feed |

### Outputs
| Output | Consumer |
|--------|----------|
| Quoted price (amount + currency + breakdown) | M4 (shown to customer at booking) |
| Final charge (post-confirmation) | M4 (invoicing / payment) |
| Per-parcel cost floor | M5, M6 (algorithm feasibility checks) |

---

## M3 — Serviceability & Grid Management

### Business Logic / Requirements
- Maintain a **rectangular tile grid** over each city's **urban serviceable area**, bounded by pincode list.
- A tile is the atom of DA assignment: **one DA per tile** in the stable assignment period.
- **Grid creation and updates:**
  - Weights: **70% historical** order density + **30% current** (live) demand.
  - Look-back window for dynamic allocation: **at least the past 7 days** of order data.
  - Target: **~70% DA utilisation** per tile (neither over nor under capacity).
  - Grid boundaries may be auto-proposed by the system but require **human approval** before going live:
    - **Station manager** can approve changes within their city.
    - **Admin** can approve changes across all cities.
- Geometric constraints:
  - Tiles must **tessellate** with no gaps or overlaps over the urban polygon.
  - Each scheduled hub-consolidation route (van cron) must **touch a vertex** of the grid — grid design and van routing are co-designed.
- **No-DA state:** if a tile has no assigned DA, system must immediately notify the station manager for manual mitigation.
- **Non-urban / rural pincodes** are outside scope; service is refused for those at booking.

### Inputs
| Input | Source |
|-------|--------|
| Urban pincode list per city | Admin configuration |
| Historical order data (7+ days) | M4 order history |
| Live order demand (current day) | M4 live feed |
| Admin / station manager approval action | M1 + UI |

### Outputs
| Output | Consumer |
|--------|----------|
| Tile definitions (geometry, ID, city) | M5 (DA assignment), M6 (van routing) |
| DA-to-tile stable assignment map | M5 |
| Serviceability check API (`is_pincode_serviceable(city, pincode) → bool`) | M4 (booking) |
| Grid vertex list (for van route graph) | M6 |
| No-DA alert for a tile | M10 (escalation), notification system |

---

## M4 — Order Booking & Shipment Lifecycle

### Business Logic / Requirements
- Central module managing the **canonical state machine** of a shipment from booking to final delivery (or RTO).
- **Booking flow:**
  1. Verify serviceability of both origin and destination pincodes (via M3).
  2. Compute price quote (via M2) and show to customer before confirmation.
  3. Assign a **predicted flight** for the air leg and generate a **customer-facing ETA** (via M9).
  4. On customer confirmation, create the shipment record and emit a `shipment.created` event.
- **State machine (high-level):**
  `BOOKED → PICKUP_ASSIGNED → PICKED_UP → AT_ORIGIN_HUB → IN_BAG → DEPARTED → IN_TRANSIT → AT_DEST_HUB → OUT_FOR_DELIVERY → DELIVERED`
  With terminal failure branches: `PICKUP_FAILED`, `DELIVERY_FAILED`, `RTO_INITIATED`, `RTO_COMPLETED`.
- **Customer tracking:** every state transition is visible to the customer with a human-readable status and ETA update.
- **B2B portal:** business users can book in bulk, view invoices, access reporting, and integrate via API.

### Inputs
| Input | Source |
|-------|--------|
| Booking request (addresses, package dims, customer info) | Customer / B2B API |
| Serviceability check result | M3 |
| Price quote | M2 |
| Predicted flight + ETA | M9 |
| State-change events from all other modules | Event bus (M5–M11) |

### Outputs
| Output | Consumer |
|--------|----------|
| `shipment.created` event (shipment ID, pickup address, tile ID, ETA) | M5, M8, M10 |
| Shipment state transitions | M10, M11, customer UI |
| ETA updates | Customer notification |
| Confirmed price / invoice | M2, B2B billing |

---

## M5 — DA Assignment & Dispatch

### Business Logic / Requirements
- Assign each new shipment pickup to a DA **as fast as possible** (low-latency, near-real-time).
- Assignment logic:
  1. Look up the tile for the pickup address (from M3) → select the DA assigned to that tile.
  2. If the tile has no DA, surface a no-DA alert (to M10/station manager) and hold the order.
- **Priority queue (PQ) per DA:** orders are ranked by a **closer-first** heuristic — the pickup that is geographically closest to the DA's current position (last known GPS) is prioritised.
- **Cron-meeting constraint (hard):** before inserting a new order into a DA's PQ, the system must verify that the DA can still reach the **hub consolidation van meeting point** on time given the updated queue and current ETAs. If the cron handoff would be broken, the new order is **deferred or held**, not inserted blindly.
- **Shift window:** DAs operate across ~16 hours in 2 shifts (exact times TBD in ops). Assignment respects active shift.
- **No third-party fallback:** if a DA is unavailable and mitigation fails, the station manager resolves it — the system does not auto-route to any 3P carrier.

### Inputs
| Input | Source |
|-------|--------|
| `shipment.created` event | M4 |
| DA-to-tile assignment map | M3 |
| DA current GPS location | DA mobile app |
| Cron van meeting point and scheduled time | M6 |
| DA active/inactive status and shift schedule | HR/ops config |

### Outputs
| Output | Consumer |
|--------|----------|
| `da.assigned` event (DA ID, shipment ID, pickup ETA) | M4 (state update), DA app |
| Updated DA priority queue | DA app |
| Cron feasibility check result | Internal; if fail → alert to M10 |
| No-DA alert for a tile | M10, station manager |

---

## M6 — Van Routing & Scheduling

### Business Logic / Requirements
- Compute an **ordered sequence of stops** for each hub consolidation van covering its assigned grid area.
- Route graph: van stops are at **grid vertices** (tile corners / defined stop points from M3).
- **Nightly replan is the default:** routes are re-optimised once per night and locked for the following operating day. Intraday changes are strongly discouraged.
  - If an intraday change is genuinely required, it must **notify the station manager** and (depending on severity) require **approval** before taking effect.
- Route optimisation uses the same **70% historical / 30% current** weighting spirit as grid sizing (shared planning philosophy).
- Key constraints for the optimiser:
  - **Packet count** on the run (van capacity).
  - **Time window to reach hub** (or SLA for that leg).
  - **Per-parcel cost floor** from M2 (cost-aware routing).
- **Admin and station manager** can manually override any route at any time with audit log entry.

### Inputs
| Input | Source |
|-------|--------|
| Grid vertex list per city | M3 |
| Historical stop + demand data (7+ days) | M4 order history |
| Current-day order demand | M4 live feed |
| Van capacity (max packets) | Fleet configuration |
| Hub operating time window | Hub ops configuration |
| Per-parcel cost floor | M2 |
| Manual override action | Station manager / admin (M1) |

### Outputs
| Output | Consumer |
|--------|----------|
| Nightly route plan per van (ordered stop list, ETA per stop) | Van driver app, M5 (cron meeting point), M10 |
| Route-change notification | Station manager notification, M10 |
| Route override audit log entry | M1 audit log |

---

## M7 — Hub Operations & Sortation

### Business Logic / Requirements
- Governs all physical and digital operations **inside a city hub**: inbound receiving, sortation, stand assignment, bag management, and manifest generation.
- **Inbound flow:**
  1. Packet arrives (from DA handoff or van) → scan barcode (M8).
  2. System resolves the **stand number** from the barcode data (destination / flight key).
  3. Hub operator places packet on the designated stand.
- **Bag management:**
  - Packets bound for the same flight are grouped into a **flight bag**.
  - Each bag is tagged with a **QR code** encoding: (1) flight number, (2) physical stand number.
  - The bag QR also references all packet IDs inside it.
  - **Stand overflow:** if a stand is full, system allows reassignment of the bag to an alternate stand, triggering a re-label workflow.
- **Manifest:** system auto-generates a **per-bag, per-flight manifest** (list of all packets with their barcodes and destination data). Not manual.
- **Flight bag reschedule (low-weight rule):**
  - If a bag is underweight (threshold TBD), system may propose moving it to the next available flight.
  - The move is only allowed if **every parcel in that bag** still meets its SLA commitment under the later flight's departure time. If even one parcel would breach SLA, the bag must fly on the originally assigned flight.
- **Hub overload mitigation:** system must not silently drop SLAs. If hub is at capacity, it must surface the condition for supervisor action (detailed tactic TBD: throttle bookings, add wave, call supervisor).
- **Destination hub is symmetric:** exactly the same sortation and stand logic applies on the receiving end after flight lands (post-M9 handover).

### Inputs
| Input | Source |
|-------|--------|
| Barcode scan events (packet arrival) | M8 |
| Stand/sort plan (destination → stand mapping) | Admin-configured; updated by flight assignment |
| Assigned flights per destination / time window | M9 |
| Parcel SLA commitment times | M4, M10 |
| Stand capacity configuration | Physical hub config |

### Outputs
| Output | Consumer |
|--------|----------|
| Stand assignment per packet | Hub operator UI / scan gun |
| Bag creation and bag ID | M8 (bag QR label), M9 (manifest handover) |
| System-generated manifest (per bag, per flight) | M9, airline handover |
| Bag reschedule event (approved or denied) | M9, M10 |
| Hub overload alert | M10, supervisor |
| `packet.at_hub` scan event | M4 (state update), M10 |

---

## M8 — Barcode, Label & Scanning

### Business Logic / Requirements
- Every parcel gets a **globally unique barcode ID** at the moment a DA attaches a label during first-mile pickup — this is the **single identity** for the parcel's entire journey.
- **Label data (minimum):**
  - Parcel unique ID
  - Destination hub ID
  - Final destination sort key (city, pincode)
  - Stand / sort plan pointer (resolved at hub scan-in)
- **Bag label (QR):**
  - Flight number
  - Physical stand number
  - References to all parcel IDs inside the bag
- **Scan events are append-only** (never modified or deleted) — this is the physical-digital audit trail.
- Scan points in the journey: DA pickup attach → origin hub in-scan → bag seal → airport handover → destination hub in-scan → DA pickup for last mile → delivery confirmation.
- **Stand-full reassignment:** when a bag moves to a new stand, system generates a new bag label and records both the old and new stand in the scan event log.
- Scanning is triggered by DA app (mobile scan), hub scan guns, or airport handheld devices. All write to the same event ledger.

### Inputs
| Input | Source |
|-------|--------|
| Shipment record (parcel ID, addresses, sort plan) | M4 |
| Stand assignment at hub | M7 |
| Flight assignment | M9 |
| Physical scan action (DA app, hub gun, airport device) | Field device |

### Outputs
| Output | Consumer |
|--------|----------|
| Barcode / label data (printable format) | DA app, hub printer |
| Bag QR code (printable) | Hub printer |
| `scan.event` record (parcel ID, location, timestamp, actor) | M4 (state update), M7 (hub logic), M10 (SLA check), audit log |
| Barcode lookup API (`barcode → current parcel state + history`) | All modules, customer tracking |

---

## M9 — Airline & Flight Integration

### Business Logic / Requirements
- Maintain a live **flight schedule** (per route, per airline partner) including scheduled departure/arrival, cutoff times for cargo handover, and actual live status.
- **Flight assignment** is done at hub level: for a given destination city and required departure window, assign the best flight (earliest that satisfies SLA + bag cutoff time).
- **Customer-facing ETA** is derived from the **predicted (committed) flight** plus ground leg buffers.
- **Hub–airport cron ("handover"):** scheduled runs between hub and airport must arrive at the airport before the airline's cargo cutoff time. System tracks whether each cron departure is on track.
- **Responsibility split on handover failure:**
  - If **our cron** is late due to our own processing → our responsibility; rebook on next flight.
  - If **airline processing time** blocks loading after we have handed over → airline's responsibility per contract. System records a reason code and shows "next-flight" status to the customer.
- **Multi-hop:** for routes with intermediate airports, each air leg is independently assigned a flight. Ground transit between intermediate airports is our responsibility (triggers a new M5/M6 cycle).
- Track post-landing status via **airline / GHA APIs** and push status events into the shipment lifecycle.

### Inputs
| Input | Source |
|-------|--------|
| Available flights, schedules, cutoff times | Airline partner APIs / agent-provided feed |
| Live flight status (departure, arrival, delays) | Airline / GHA APIs |
| Parcel SLA commitment (latest arrival time) | M4, M10 |
| Hub bag ready time | M7 |
| Cron departure time (actual) | M6 van/cron schedule |

### Outputs
| Output | Consumer |
|--------|----------|
| Assigned flight per bag/destination | M7 (sort plan, manifest), M4 (ETA) |
| Predicted flight for ETA | M4 |
| Flight status events (`departed`, `landed`, `delayed`, `missed_cutoff`) | M4 (state update), M10 |
| Reason code (our fault vs airline fault) | M11 (exception handling) |
| GHA handover confirmation | M8 (scan event), M10 |

---

## M10 — SLA Monitoring & Escalation

### Business Logic / Requirements
- Compute and track the **SLA state** on every leg of every active shipment in real-time.
- **Legs monitored:**
  1. Pickup (DA to hub) — time from booking to hub in-scan
  2. Hub processing — time from hub in-scan to bag seal
  3. Hub-to-airport cron — time to airport vs cutoff
  4. Air transit — departure to landing (per segment)
  5. Destination hub processing — mirror of origin
  6. Last-mile delivery — hub out-scan to delivered confirmation
- **State classification per leg:** `GREEN` / `AMBER` / `RED`.
- **RED state triggers:** system must **surface** the breach to the assigned supervisor (and/or station manager per RACI — TBD). The supervisor must take a defined **action** — not just receive a notification.
- **No-DA grid alert** from M3 and M5 is also routed through this module for visibility.
- **SLA for the hub–airport handover:** must be measurable in minutes (exact threshold TBD with ops).
- All SLA events and escalation actions are **audit-logged**.

### Inputs
| Input | Source |
|-------|--------|
| Scan events with timestamps | M8 |
| Flight status events | M9 |
| Van route ETA per stop | M6 |
| DA assignment + pickup ETA | M5 |
| Committed SLA per shipment | M4 |
| Supervisor / station manager action | M1 (role), escalation UI |

### Outputs
| Output | Consumer |
|--------|----------|
| Per-leg SLA state (GREEN / AMBER / RED) | Operations dashboard |
| `sla.red` escalation event (leg, shipment, assigned supervisor) | Supervisor notification system |
| Escalation action audit log | Audit log (NFR-1) |
| SLA breach reason code | M11 (exception handling) |

---

## M11 — Exception Handling, Call Center & RTO

### Business Logic / Requirements
- Handle all **failure states** in the shipment lifecycle: failed pickup, failed delivery, missed handover, and full Return-to-Origin.
- **Failure capture flow:**
  1. DA (or hub/airport actor) marks an attempt as `FAILED` in their app.
  2. System prompts for a structured **reason code** (e.g. customer absent, address not found, access denied).
  3. Record is routed to the **call center queue**.
  4. Call center agent contacts customer, captures feedback, and inputs a **rescheduled time window**.
  5. System auto-schedules the next attempt.
- **DA non-attempt penalty:** if customer or system evidence (e.g. no GPS movement, contradicted by camera) indicates the DA did not actually attempt pickup/delivery, a **penalty flag** is raised (payroll / HR integration scope TBD — product must at minimum flag it; full workflow is a later phase).
- **RTO rule:** after **N failed delivery attempts** within a defined window (N and days TBD with ops policy), shipment transitions to `RTO_INITIATED` and is routed back to origin following the same hub/flight/DA flow in reverse.
- **Flight miss / rebooking:** when M9 raises a missed-cutoff event, this module handles customer communication and coordinates the next-flight rebooking state.
- **Call center hours** vs 16h field ops gap: overnight failure records must be held in queue and actioned at call center open time (exact handling TBD).

### Inputs
| Input | Source |
|-------|--------|
| Failed-attempt event (with reason code) | DA app, hub operator |
| SLA breach events | M10 |
| Missed-cutoff reason code | M9 |
| Call center agent feedback (reschedule window, customer response) | Call center UI |
| DA GPS / activity log (for non-attempt detection) | DA app |

### Outputs
| Output | Consumer |
|--------|----------|
| Rescheduled pickup / delivery task | M5 (new DA assignment) |
| Customer notification (failure reason, new ETA) | M4, notification system |
| DA penalty flag | HR/payroll integration |
| `rto.initiated` event | M4 (state update), M5 (reverse-route pickup), M6 |
| Call center ticket with full shipment context | Call center app |

---

## Cross-Cutting Concerns (not a separate module — each team must honour these)

| Concern | Rule |
|---------|------|
| **Audit log** | All material scan events, manifest events, role changes, grid changes, and escalation actions are append-only and queryable (NFR-1, NFR-2) |
| **Human override** | Grids (M3), van routes (M6), and exceptions must support admin and station manager overrides with audit trail (NFR-2) |
| **Nightly stability** | Van and grid replans are nightly by default; intraday changes require governance + notification (NFR-3) |
| **Cost alignment** | Utilisation target (~70% DA) and per-parcel cost floor are first-class inputs to M3, M5, M6 (NFR-4) |
| **City-scoped design** | All data models must carry `city_id`; station manager permissions are enforced at city boundary (NFR-5, M1) |
| **Fallback UX** | Hub–airport and airline dependencies must have defined delay messaging and rebooking visibility surfaced to the customer (NFR-6) |

---

## Dependency Map (build order guide)

```
M1 (Auth)
  └─► M2 (Pricing)          [no upstream module deps]
  └─► M3 (Grid)             [reads M4 order history — wire via event/batch]
  └─► M8 (Barcode)          [no upstream module deps; printable service]
      └─► M4 (Orders)       [depends on M2, M3 serviceability check, M9 ETA]
          └─► M5 (DA)       [depends on M3 tile map, M6 cron schedule]
          └─► M6 (Van)      [depends on M3 vertices, M4 demand]
          └─► M7 (Hub)      [depends on M8 scans, M9 flight assignment]
          └─► M9 (Airline)  [depends on M4 SLA commitment, M7 bag ready]
          └─► M10 (SLA)     [depends on M4–M9 events]
          └─► M11 (Exceptions) [depends on M10 escalations, M9 missed-cutoff]
```

> **Recommended parallel tracks for a team of 4–6:**
> - **Track A:** M1 + M2 (Auth & Pricing — pure logic, no external deps)
> - **Track B:** M3 + M8 (Grid definitions + Barcode service — foundational data)
> - **Track C:** M4 (Order Lifecycle — integrates Track A and B outputs)
> - **Track D:** M5 + M6 (DA and Van — depend on M3; can mock M4 events early)
> - **Track E:** M7 + M9 (Hub Ops and Airline — can mock scan events and flight feeds)
> - **Track F:** M10 + M11 (SLA and Exceptions — consumes events from all; build last but design schema early)

---

*Document version: 0.1 — derived from PRD v1.0 (2026-04-25). Next step: per-module technical design doc (data models, API contracts, algorithm specs).*
