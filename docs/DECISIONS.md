# Assumptions & Design Decisions — One-Day Delivery Platform

**Purpose:** Single source of truth for every design decision and assumption made during development.  
**Scope:** All modules M1–M11, cross-cutting concerns, and infrastructure.  
**Maintained by:** All contributors. Update this document whenever a decision is made, changed, or invalidated.

---

## How to Use This Document

- **Before making a decision:** check if it is already recorded here as OPEN or DECIDED.
- **When making a new decision:** add a new entry under the relevant module. Fill in all fields. Set status to DECIDED.
- **When changing a decision:** do NOT delete the old entry. Change its status to SUPERSEDED, add a note pointing to the new entry, and create a new entry with the updated decision. This preserves the full history.
- **When a decision is pending:** record it as OPEN with the options being evaluated. This prevents duplicate work.

---

## Status Legend

| Status | Meaning |
|---|---|
| `DECIDED` | Decision made. In effect. Do not change without a new entry. |
| `OPEN` | Decision pending. A placeholder or default may be in use. |
| `SUPERSEDED` | Decision has been replaced. See the "Superseded by" field for the new entry. |
| `ASSUMED` | Not explicitly decided — assumed based on PRD or context. Needs validation. |

---

## Decision ID Format

`{MODULE}-D-{NNN}` — e.g. `M4-D-001` for the first decision in M4.  
`XC-D-{NNN}` — for cross-cutting decisions that span multiple modules.

---

## Master Timeline

Chronological log of every decision entry and update. Quick reference for "what changed when."

| Date | ID | Module | Summary | Action |
|---|---|---|---|---|
| 2026-05-10 | XC-D-001 | Cross-cutting | Event transport: Kafka async, HTTP sync | DECIDED |
| 2026-05-10 | XC-D-002 | Cross-cutting | Audit trail is append-only everywhere | DECIDED |
| 2026-05-10 | XC-D-003 | Cross-cutting | All data models carry city_id | DECIDED |
| 2026-05-10 | XC-D-004 | Cross-cutting | 70/30 historical/current demand weighting | ASSUMED |
| 2026-05-10 | XC-D-005 | Cross-cutting | Target DA utilisation ~70% | ASSUMED |
| 2026-05-10 | XC-D-006 | Cross-cutting | Scale target: medium (~10k orders/day) | DECIDED |
| 2026-05-10 | XC-D-007 | Cross-cutting | DB: PostgreSQL; Queue: Kafka; Runtime: Java 21 + Spring Boot 3.2 | DECIDED |
| 2026-05-10 | XC-D-008 | Cross-cutting | Nightly stability: grids and routes replan once nightly | DECIDED |
| 2026-05-10 | XC-D-009 | Cross-cutting | No third-party last-mile carriers in v1 | DECIDED |
| 2026-05-10 | M4-D-001 | M4 | B2B and B2C use separate API endpoints | DECIDED |
| 2026-05-10 | M4-D-002 | M4 | B2C payment: Razorpay prepaid at booking | DECIDED |
| 2026-05-10 | M4-D-003 | M4 | B2B payment: monthly credit invoice, no gateway | DECIDED |
| 2026-05-10 | M4-D-004 | M4 | Cancellation allowed up to PICKED_UP state | DECIDED |
| 2026-05-10 | M4-D-005 | M4 | ETA logic: placeholder rule-based in v1 | OPEN |
| 2026-05-10 | M4-D-006 | M4 | State machine: draft from MODULES.md, needs ops sign-off | OPEN |
| 2026-05-10 | M4-D-007 | M4 | Order-to-Parcel cardinality: 1:1 in v1, migrate to 1:N later | OPEN |
| 2026-05-10 | M4-D-008 | M4 | Shipment reference format: 1DD-{CITY}-{YYYYMMDD}-{SEQ} | DECIDED |
| 2026-05-10 | M4-D-009 | M4 | Notifications: SMS + Email + WhatsApp on every state change | DECIDED |
| 2026-05-10 | M4-D-010 | M4 | Booking cutoff time: 10:00 AM IST (config-driven default) | ASSUMED |
| 2026-05-10 | M4-D-011 | M4 | Razorpay order created server-side; client renders checkout | DECIDED |
| 2026-05-10 | M4-D-012 | M4 | B2C refund on cancellation: initiate sync, confirm via webhook | OPEN |
| 2026-05-11 | M4-D-006 | M4 | State machine revised: 5 states added, `IN_TRANSIT` removed, cron stages added | UPDATED |
| 2026-05-11 | M4-D-005 | M4 | ETA computation delegated entirely to M9; null if M9 unavailable | DECIDED |
| 2026-05-12 | M4-D-005 | M4 | ETA corrected to two-stage model: rule-based at booking (never null) + accurate ETA at origin hub | UPDATED |
| 2026-05-11 | M4-D-007 | M4 | Order:Parcel cardinality confirmed as 1:1 in v1; upgrade path documented | DECIDED |
| 2026-05-11 | M4-D-013 | M4 | C2C added as first-class customer_type; same payment flow as B2C, different rate card | DECIDED |
| 2026-05-11 | M4-D-014 | M4 | delivery_type field added (INTERCITY / SAME_CITY); derived at booking from city comparison | DECIDED |
| 2026-05-11 | M4-D-015 | M4 | Consumed Kafka events NOT stored in DB; only state transition effect stored | DECIDED |
| 2026-05-11 | M4-D-016 | M4 | Idempotency-Key header required on all booking POST endpoints | DECIDED |
| 2026-05-11 | M4-D-017 | M4 | B2B credit check + booking are atomic via SELECT FOR UPDATE on b2b_accounts | DECIDED |
| 2026-05-11 | M4-D-018 | M4 | Circuit breakers (Resilience4j) on M2, M3, and Razorpay calls | DECIDED |
| 2026-05-11 | M4-D-019 | M4 | COD excluded from v1 and not planned without explicit business decision | DECIDED |
| 2026-05-12 | M4-D-019 | M4 | Reversed: COD is in v1 for B2C and C2C; B2B is credit-only | UPDATED |
| 2026-05-12 | M4-D-020 | Cross | International delivery not part of business plan; removed from out-of-scope list | UPDATED |
| 2026-05-11 | M4-D-020 | M4 | GST 18% applied to all bookings; breakdown in all pricing responses | DECIDED |
| 2026-05-12 | M4-D-020 | M4 | Corrected: all pricing + GST logic owned by M2; M4 stores and forwards only | UPDATED |
| 2026-05-11 | M4-D-021 | M4 | B2B webhook delivery: HMAC-signed state events to registered URL | DECIDED |
| 2026-05-12 | BD-001 | M4 | Cancellation policy: up to which state + fees — pending business decision | OPEN |
| 2026-05-12 | BD-002 | M4 | Payment gateway selection (Razorpay vs alternatives) — pending business decision | OPEN |
| 2026-05-10 | M8-D-001 | M8 | Barcode format: QR Code (2D) | DECIDED |
| 2026-05-10 | M8-D-002 | M8 | Parcel ID format: {CITY}-{YYYYMMDD}-{6-digit-seq} | DECIDED |
| 2026-05-10 | M8-D-003 | M8 | Parcel ID generated by M4, registered with M8 | DECIDED |
| 2026-05-10 | M8-D-004 | M8 | Scan events are append-only; never updated or deleted | DECIDED |
| 2026-05-10 | M8-D-005 | M8 | Offline scanning: online-only in v1 | DECIDED |
| 2026-05-10 | M8-D-006 | M8 | Scan idempotency: client-supplied idempotency key (recommended) | OPEN |
| 2026-05-10 | M8-D-007 | M8 | QR error correction level: M (15% recovery) | DECIDED |
| 2026-05-10 | M8-D-008 | M8 | QR generation library: ZXing | DECIDED |
| 2026-05-10 | M8-D-009 | M8 | QR label bytes cached in Redis, TTL 24h | DECIDED |
| 2026-05-10 | M8-D-010 | M8 | Bag label QR: embed parcel_ids for ≤100 parcels; manifest_url for >100 | DECIDED |
| 2026-05-10 | M8-D-011 | M8 | Scan event table: partition by month at 50M rows | DECIDED |
| 2026-05-10 | M8-D-012 | M8 | Airline barcode standard: validate IATA compatibility before go-live | OPEN |
| 2026-05-10 | M8-D-013 | M8 | B2B label templates: pluggable strategy pattern; customisation out of scope v1 | DECIDED |

---

## Cross-Cutting Decisions

---

### XC-D-001 — Event Transport Architecture

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | docs/MODULES.md §Event Architecture; team discussion |

**Decision:**  
- **Kafka** for all async fan-out communication (scan events, state changes, SLA triggers, flight status)
- **Synchronous HTTP / direct in-process call** for operations where the result is needed immediately in the same request (e.g. M4 calling M2 for a price quote at booking time)
- **No database polling** between modules

**Rationale:**  
Kafka decouples producers and consumers. When a module is eventually extracted into its own service, no code changes are needed — only the deployment changes. Database polling degrades under load and is an anti-pattern at scale.

**Change log:**  
_(No changes yet)_

---

### XC-D-002 — Append-Only Audit Trail

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | CLAUDE.md Key Design Invariants |

**Decision:**  
Scans, manifests, role changes, state history, grid overrides, and route overrides are **never mutated**. Records are only inserted, never updated or deleted.

**Rationale:**  
Required for regulatory compliance, ops debugging, and SLA dispute resolution. Any correction is a new record pointing to the old one.

**Change log:**  
_(No changes yet)_

---

### XC-D-003 — City-Scoped Data Model

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | CLAUDE.md NFR-5; MODULES.md M1 |

**Decision:**  
Every entity that belongs to a city carries a `city_id` column. Station manager JWT claims are enforced at the service layer against this field. Cross-city queries require Admin role.

**Change log:**  
_(No changes yet)_

---

### XC-D-004 — Demand Weighting: 70% Historical / 30% Current

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | ASSUMED |
| **Source** | CLAUDE.md Key Design Invariants |

**Assumption:**  
Grid sizing (M3) and van route planning (M6) weight historical demand at 70% and current-day demand at 30%. Look-back window is at least 7 days.

**Needs validation:** Exact weighting and window length should be confirmed with ops before M3 and M6 implementation.

**Change log:**  
_(No changes yet)_

---

### XC-D-005 — Target DA Utilisation ~70%

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | ASSUMED |
| **Source** | CLAUDE.md Key Design Invariants |

**Assumption:**  
DA utilisation target is ~70%. This is the cost floor for M3 (grid sizing), M5 (DA assignment), and M6 (van routing). Algorithms must not optimise purely for speed at the expense of this floor.

**Needs validation:** Exact threshold and measurement definition to be confirmed with ops.

**Change log:**  
_(No changes yet)_

---

### XC-D-006 — Scale Target

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | Team discussion, 2026-05-10 |

**Decision:**  
Design for **medium scale (~10,000 orders/day)** across 5 cities at launch. Build for this ceiling; do not over-engineer for 10× upfront. Scaling hooks (connection pooling, Kafka partitioning, read replicas) are planned from day one but not all activated.

**Change log:**  
_(No changes yet)_

---

### XC-D-007 — Technology Stack

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | CLAUDE.md |

**Decision:**  
- **Runtime:** Java 21, Spring Boot 3.2, Maven multi-module monolith
- **Database:** PostgreSQL (single instance to start; read replica when needed)
- **Message bus:** Kafka
- **Schema migrations:** Flyway
- **Build:** Maven

**Change log:**  
_(No changes yet)_

---

### XC-D-008 — Nightly Stability

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | CLAUDE.md Key Design Invariants; MODULES.md M3, M6 |

**Decision:**  
Grids (M3) and van routes (M6) are replanned **once nightly**. Intraday changes are strongly discouraged and require station manager or admin approval with an audit log entry. No silent intraday mutations.

**Change log:**  
_(No changes yet)_

---

### XC-D-009 — No Third-Party Last-Mile Carriers in v1

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | CLAUDE.md What This Is |

**Decision:**  
v1 uses only in-house DAs for first and last mile. No 3P carrier (Dunzo, Porter, Shadowfax, etc.) fallback. If a DA is unavailable, station manager resolves it manually.

**Change log:**  
_(No changes yet)_

---

## M1 — Auth & Role Management

_No design doc written yet. Decisions will be added when M1 design is authored._

### Placeholder Assumptions

| ID | Assumption | Status | Source |
|---|---|---|---|
| M1-D-001 | 10 actor roles as listed in MODULES.md | ASSUMED | MODULES.md M1 |
| M1-D-002 | B2B machine-to-machine uses API keys; human actors use short-lived JWTs | ASSUMED | MODULES.md M1 |
| M1-D-003 | JWT claims carry city_id; station manager actions outside their city are rejected at service layer | ASSUMED | MODULES.md M1 |
| M1-D-004 | All role grants and revocations are append-only audit-logged | DECIDED | CLAUDE.md |

---

## M2 — Pricing & Costing Engine

_No design doc written yet. Decisions will be added when M2 design is authored._

### Placeholder Assumptions

| ID | Assumption | Status | Source |
|---|---|---|---|
| M2-D-001 | Volumetric weight divisor is contractually set per B2B account | ASSUMED | MODULES.md M2 |
| M2-D-002 | Pricing data is versioned; historical orders re-compute against the rate card active at booking time | DECIDED | MODULES.md M2 |
| M2-D-003 | B2B accounts have account-level negotiated rate tables; B2C uses a published rate card | DECIDED | MODULES.md M2 |
| M2-D-004 | Per-parcel cost floor is internal only; not exposed to customers | DECIDED | MODULES.md M2 |

---

## M3 — Serviceability & Grid Management

_No design doc written yet. Decisions will be added when M3 design is authored._

### Placeholder Assumptions

| ID | Assumption | Status | Source |
|---|---|---|---|
| M3-D-001 | Grid vertex/tile rules (G1–G4) are unresolved; block full M3 implementation | OPEN | CLAUDE.md §Open Questions |
| M3-D-002 | Each tile has exactly one DA in the stable assignment period | ASSUMED | MODULES.md M3 |
| M3-D-003 | Tiles must tessellate with no gaps or overlaps over the urban polygon | DECIDED | MODULES.md M3 |
| M3-D-004 | Each scheduled van cron route must touch a vertex of the grid | DECIDED | MODULES.md M3 |
| M3-D-005 | Non-urban / rural pincodes are refused at booking | DECIDED | MODULES.md M3 |
| M3-D-006 | Grid boundary changes require human approval (station manager for city, admin for all) | DECIDED | MODULES.md M3 |

---

## M4 — Order Booking & Shipment Lifecycle

---

### M4-D-001 — B2B and B2C API Separation

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | Team discussion; docs/M4/M4-ORDERS-DESIGN.md §5 |

**Decision:**  
B2B and B2C use **separate API endpoints**: `POST /api/v1/b2c/shipments` and `POST /api/v1/b2b/shipments`. Different request shapes, different auth mechanisms, different business rules.

**Rationale:**  
A single endpoint with a `customerType` switch creates a complex branching payload and leaks B2B concepts into the B2C contract. Separate endpoints are independently versioned and documented.

**Implications:**  
- Two controllers in M4 (`B2cShipmentController`, `B2bShipmentController`)
- Separate request DTOs with no shared inheritance
- Separate API keys for B2B machine-to-machine; JWT for B2C

**Change log:**  
_(No changes yet)_

---

### M4-D-002 — B2C Payment: Razorpay Prepaid

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | Team discussion; docs/M4/M4-ORDERS-DESIGN.md §9 |

**Decision:**  
B2C customers pay at booking time via **Razorpay**. Shipment is confirmed only after payment capture. Flow: server creates Razorpay order → client renders checkout → client submits `{razorpay_order_id, razorpay_payment_id, razorpay_signature}` → server verifies HMAC-SHA256 → captures payment → creates shipment.

**Implications:**  
- `PaymentTransaction` entity in M4
- Razorpay webhook handler required for async events (capture, failure, refund)
- Shipment creation and payment capture must be atomic (or compensated on failure)

**Change log:**  
_(No changes yet)_

---

### M4-D-003 — B2B Payment: Monthly Credit Invoice

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | Team discussion; docs/M4/M4-ORDERS-DESIGN.md §9.3 |

**Decision:**  
B2B bookings are on credit terms. No payment gateway is invoked at booking time. The booking price is logged against the B2B account's outstanding balance. A separate billing service (out of scope v1) generates monthly invoices. M4 checks `credit_limit_paise` before accepting a booking.

**Implications:**  
- `B2bAccount` entity with `credit_limit_paise` and `payment_terms_days`
- M4 must check outstanding balance at booking time (requires a balance query)
- Billing/invoice generation is a v2 feature

**Change log:**  
_(No changes yet)_

---

### M4-D-004 — Cancellation Policy

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Last updated** | 2026-05-12 |
| **Status** | ASSUMED — pending business decision BD-001 |
| **Source** | Team discussion; docs/M4/M4-ORDERS-DESIGN.md §5.1 |

**Current assumption:**  
Cancellation is allowed in states: `BOOKED`, `PICKUP_ASSIGNED`, `PICKED_UP`.  
After `PICKED_UP`, cancellation is rejected with `409 Conflict`.

**Pending:** See **BD-001** for the full set of business questions:
- Up to which state should cancellation be allowed?
- Should cancellation incur a fee?
- Different policies per customer type?

**Engineering note:** The state machine is ready to enforce any boundary — this is a configuration change once the business decides.

**Change log:**  
- **2026-05-12** — Status changed from DECIDED to ASSUMED; full policy deferred to BD-001

---

### M4-D-005 — ETA Computation

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Last updated** | 2026-05-11 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §4 (KDD-4) |

**Decision:**  
All ETA logic is **entirely owned by M9**. M4 calls `EtaPort.fetchEta(shipmentId, state, context)` whenever it needs an ETA and stores the result as-is. M4 has no fallback, no cutoff-time config, and no ETA computation of any kind.

M4 calls EtaPort at:
- Quote time (pre-booking context) — result shown in quote response
- Booking confirmation (state=`BOOKED`) — result stored as `eta_promised`
- `AT_ORIGIN_HUB` transition — result stored as `eta_updated`; customer notified

All edge cases (M9 unavailability, missing flight data, same-city vs intercity differences) are handled by M9.

**Implications:**
- `EtaPort` has a single method: `fetchEta(shipmentId, state, context)`
- M4 has no `cutoff-time-ist` config
- M4 stores whatever M9 returns; no validation of the ETA value
- Quote API response includes `eta_estimated` from M9
- Notification sent when `eta_updated` is set

**Change log:**  
- **2026-05-10** — Initially OPEN; placeholder rule-based logic considered
- **2026-05-11** — DECIDED: delegate to M9; null acceptable if unavailable *(incorrect)*
- **2026-05-12** — UPDATED: Two-stage model with M4 rule-based fallback *(also incorrect)*
- **2026-05-12** — FINAL: M4 has zero ETA logic; single EtaPort.fetchEta() call; M9 owns everything

---

### M4-D-006 — Shipment State Machine

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Last updated** | 2026-05-11 |
| **Status** | OPEN |
| **Source** | MODULES.md M4; docs/M4/M4-ORDERS-DESIGN.md §4 |

**Current state list (v2 — updated 2026-05-11):**

Happy path in order:
`BOOKED → PICKUP_ASSIGNED → PICKED_UP → HANDED_TO_VAN → AT_ORIGIN_HUB → HUB_PROCESSING → IN_BAG → DISPATCHED_TO_AIRPORT → AT_AIRPORT → DEPARTED → AT_DEST_HUB → DEST_HUB_PROCESSING → OUT_FOR_DELIVERY → DELIVERED`

Failure / terminal branches:
`PICKUP_FAILED`, `DELIVERY_FAILED`, `RTO_INITIATED`, `RTO_IN_TRANSIT`, `RTO_COMPLETED`, `CANCELLED`

**What was changed in v2:**
- Added `HANDED_TO_VAN` — DA cron handoff; DA responsibility ends; SLA Leg 1 closes here
- Added `HUB_PROCESSING` — stand assigned at origin hub; hub SLA leg start timestamp
- Added `DISPATCHED_TO_AIRPORT` — bag loaded on cron van; left the hub; cron driver custody
- Added `AT_AIRPORT` — GHA acceptance; airline has taken custody; fault line between our ops and airline
- Added `DEST_HUB_PROCESSING` — last-mile sortation at destination hub; symmetric with origin
- Removed `IN_TRANSIT` — collapsed into `DEPARTED`; no actionable operational difference between the two
- Deferred for post-v1: `FLIGHT_MISSED`, `LOST`, `DAMAGED`

**What still needs to be decided:**  
Full ops sign-off on state list and transition rules before implementation begins.

**Change log:**
- **2026-05-11** — Revised from MODULES.md draft. Added 5 states (`HANDED_TO_VAN`, `HUB_PROCESSING`, `DISPATCHED_TO_AIRPORT`, `AT_AIRPORT`, `DEST_HUB_PROCESSING`). Removed `IN_TRANSIT`. Deferred `FLIGHT_MISSED`, `LOST`, `DAMAGED` to post-v1. Full visual chart added to M4-ORDERS-DESIGN.md §4.1.

---

### M4-D-007 — Order-to-Parcel Cardinality

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Last updated** | 2026-05-11 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §5.2 (KDD-5) |

**Decision:**  
**1 booking = 1 parcel in v1.** `parcel_id` is a column on `Shipment`. Multi-parcel is explicitly deferred to post-v1.

**Upgrade path:** Add a `parcels` child table with `(id, shipment_id, parcel_id, weight_grams, state)` via Flyway migration when B2B multi-parcel is needed. No API surface change required for the single-parcel case.

**Change log:**  
- **2026-05-10** — Initially OPEN; 1:1 approach used as default
- **2026-05-11** — DECIDED: 1:1 confirmed for v1; upgrade path documented in design doc

---

### M4-D-008 — Shipment Reference Format

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §11 |

**Decision:**  
Human-readable reference: `1DD-{CITY}-{YYYYMMDD}-{5-digit-seq}` (e.g. `1DD-BLR-20260510-00042`).  
- `1DD` — platform prefix  
- `CITY` — origin city code (BLR, BOM, DEL, HYD, MAA)  
- `YYYYMMDD` — booking date in IST  
- 5-digit zero-padded daily sequence per city, resets at midnight IST

A separate internal UUID is the database PK. The `shipment_ref` is for human use and external APIs.

**Change log:**  
_(No changes yet)_

---

### M4-D-009 — Notification Channels

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | Team discussion; docs/M4/M4-ORDERS-DESIGN.md §10 |

**Decision:**  
Three channels in v1: **SMS** (MSG91 — recommended for India DLT compliance), **Email** (AWS SES), **WhatsApp** (Twilio WhatsApp Business API or Meta direct).

Notifications are dispatched **asynchronously** via a `NotificationPort` interface. M4 publishes a `notification.requested` internal event; a background handler delivers it.

**Change log:**  
_(No changes yet)_

---

### M4-D-010 — Booking Cutoff Time

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Last updated** | 2026-05-12 |
| **Status** | SUPERSEDED |
| **Superseded by** | M4-D-005 (ETA entirely owned by M9) |

**Original assumption:**  
Default booking cutoff of 10:00 AM IST used as M4's rule-based ETA fallback.

**Why superseded:**  
M4 has no ETA logic at all. Cutoff time is an ETA input that M9 will manage internally. M4 does not need to know or configure it.

**Change log:**  
- **2026-05-12** — Superseded: cutoff-time concept moved entirely into M9's domain

---

### M4-D-011 — Razorpay Order Creation

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §9.1 |

**Decision:**  
Razorpay order is created **server-side** by M4. The client receives the Razorpay `order_id` and renders the checkout. This avoids exposing the Razorpay secret key to the client.

**Change log:**  
_(No changes yet)_

---

### M4-D-012 — B2C Cancellation Refund Flow

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | OPEN |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §5.1, OD-2 |

**Current approach:**  
Refund initiated synchronously via Razorpay Refund API on cancellation. Razorpay webhook confirms completion. `refund_status` stored on `PaymentTransaction`.

**What needs to be decided:**  
- Refund processing time to communicate to customers (Razorpay SLA is 5–7 business days)
- Partial refund rules if any (e.g. if pickup has already been attempted)

**Change log:**  
_(No changes yet)_

---

### M4-D-013 — C2C as First-Class Customer Type

| Field | Value |
|---|---|
| **Date** | 2026-05-11 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §4 (KDD-1) |

**Decision:**  
`C2C` (Consumer-to-Consumer / person-to-person) is a first-class value in the `customer_type` enum alongside `B2C` and `B2B`.

**Rationale:**  
C2C shipments use the same Razorpay prepaid flow as B2C but require a different rate card from M2. Using `customer_type=C2C` allows M2 to branch cleanly on type without M4 embedding rate card selection logic.

**Implications:**
- `customer_type ENUM('B2C', 'B2B', 'C2C')` in DB schema
- Same booking endpoint as B2C: `POST /api/v1/b2c/shipments` with `customer_type: "C2C"` in body
- Same auth role: `B2C_CUSTOMER`
- M2's `computeQuote()` receives `customer_type` and selects appropriate rate card

**Change log:**  
_(First entry)_

---

### M4-D-014 — Delivery Type Field (INTERCITY vs SAME_CITY)

| Field | Value |
|---|---|
| **Date** | 2026-05-11 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §4 (KDD-2) |

**Decision:**  
`delivery_type ENUM('INTERCITY', 'SAME_CITY')` is stored on `Shipment`. Derived at booking by comparing `origin_city == dest_city`. Immutable after booking.

**Rationale:**  
Same-city shipments skip the air leg entirely. Without this field, the state machine has no way to determine which transitions are legal for a given shipment.

**Implications:**
- `SAME_CITY` path: `IN_BAG → OUT_FOR_DELIVERY` (skips 5 air leg states)
- `INTERCITY` path: `IN_BAG → DISPATCHED_TO_AIRPORT → AT_AIRPORT → DEPARTED → AT_DEST_HUB → DEST_HUB_PROCESSING → OUT_FOR_DELIVERY`
- M2 and M9 both receive `delivery_type` to adjust pricing and ETA
- No separate API endpoint for same-city; the field is derived, not input

**Change log:**  
_(First entry)_

---

### M4-D-015 — Consumed Kafka Events: Effect Only, Not Stored

| Field | Value |
|---|---|
| **Date** | 2026-05-11 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §9 (KDD-3) |

**Decision:**  
M4 does **not** persist raw Kafka event payloads to the database. Only the resulting state transition is stored: a `shipment_state_history` row with `trigger_source=KAFKA_EVENT` and `event_ref=<kafka_message_key>`.

**Rationale:**  
Kafka is the durable log for events. Duplicating raw payloads in PostgreSQL wastes storage and introduces a synchronisation problem. The Kafka message key in `event_ref` is sufficient for correlation.

**Implications:**
- `shipment_state_history.event_ref` stores the Kafka message key (not a FK, just a string reference)
- Raw event replay requires reading from Kafka directly (configured for 7-day retention minimum)
- No `consumed_events` table in M4's schema

**Change log:**  
_(First entry)_

---

### M4-D-016 — Booking Endpoint Idempotency

| Field | Value |
|---|---|
| **Date** | 2026-05-11 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §4 (KDD-6) |

**Decision:**  
`POST /api/v1/b2c/shipments` and `POST /api/v1/b2b/shipments` require an `Idempotency-Key: <uuid>` header. Duplicate requests with the same key within 24 hours return the original response (HTTP 200 + original body).

**Rationale:**  
Network retries and client-side double-taps are inevitable. Without idempotency, a Razorpay capture could succeed while the DB write fails, leaving the customer charged but without a shipment.

**Implications:**
- `idempotency_keys` table: `(key, user_id, response_status, response_body, expires_at)`
- Key is scoped to the authenticated user to prevent cross-user collision
- Nightly job purges expired rows (`expires_at < NOW()`)
- Missing header returns `400 MISSING_IDEMPOTENCY_KEY`

**Change log:**  
_(First entry)_

---

### M4-D-017 — B2B Credit Check Atomicity

| Field | Value |
|---|---|
| **Date** | 2026-05-11 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §4 (KDD-7) |

**Decision:**  
B2B credit check and `Shipment` insertion happen in the same DB transaction, with `SELECT ... FOR UPDATE` on the `b2b_accounts` row.

**Rationale:**  
Concurrent B2B bookings from the same account could otherwise race past the credit limit check. Row-level locking serialises them.

**Implications:**
- `b2b_accounts.outstanding_balance_paise` is incremented atomically in the booking transaction
- External billing service decrements this when invoices are paid (out of M4 scope)
- Lock contention is expected to be low — one lock per account, not per city

**Change log:**  
_(First entry)_

---

### M4-D-018 — Circuit Breakers on Synchronous Module Calls

| Field | Value |
|---|---|
| **Date** | 2026-05-11 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §8 (KDD-8) |

**Decision:**  
All synchronous calls from M4 to external dependencies (M2, M3, Razorpay) are wrapped with **Resilience4j circuit breakers** with explicit timeouts.

| Dependency | Timeout | Threshold |
|---|---|---|
| M2 PricingPort | 500ms | 5 failures in 10 calls |
| M3 ServiceabilityPort | 500ms | 5 failures in 10 calls |
| Razorpay capture | 3s | 3 failures in 5 calls |

On open circuit: return `503 Service Unavailable` with `Retry-After: 30`. **No stale fallback** — a wrong price or serviceability answer is worse than a clean error.

**Change log:**  
_(First entry)_

---

### M4-D-019 — COD is a v1 Payment Option for B2C and C2C

| Field | Value |
|---|---|
| **Date** | 2026-05-12 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §4 (KDD-10) |

**Decision:**  
COD (Cash on Delivery) is supported in v1 for B2C and C2C shipments. B2B is credit-only; COD does not apply to B2B.

**M4's scope for COD:**
- `payment_mode ENUM('PREPAID', 'COD')` on `Shipment`
- No Razorpay interaction at booking for COD orders
- `payment_mode` included in `shipment.created` Kafka event — M5 instructs DA to collect cash at door
- COD cancellation has no refund step (no payment collected)

**Out of M4's scope for COD:**
- Cash collection logic (M5/DA app)
- Float reconciliation and remittance (M5/finance)
- Fraud controls on COD orders (post-v1)

**Change log:**  
- **2026-05-11** — Initially decided as excluded from v1
- **2026-05-12** — Reversed: COD is in v1 for B2C and C2C per business requirement

---

### M4-D-020 — Pricing and Tax Computation Owned Entirely by M2

| Field | Value |
|---|---|
| **Date** | 2026-05-11 |
| **Last updated** | 2026-05-12 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §11.4 (KDD-9) |

**Decision:**  
All pricing logic — base rates, GST, surcharges, volumetric divisor, rate card selection — is **M2's responsibility**. M4 passes the booking context to M2 via `PricingPort.computeQuote()` and stores the result as-is. M4 does not know the GST rate, does not compute tax, and has no pricing config.

**Rationale:**  
Clear separation of concerns. Pricing rules change frequently (GST revisions, rate card updates, new surcharges). Having any pricing logic in M4 creates drift and debugging confusion. M2 is the single place to change.

**Implications:**
- `QuoteResult` from M2 contains: `quoted_price_paise`, `tax_paise`, `total_price_paise`, `breakdown`, `rate_card_version`
- M4 columns are named `tax_paise` (not `gst_paise`) — M4 does not know the tax type
- No `gst.rate` property in M4's `application.yml`
- B2B GSTIN stored on `B2bAccount` is passed to M2 as context; M4 does not interpret it

**Change log:**  
- **2026-05-11** — Initially recorded as M4 computing GST 18%
- **2026-05-12** — CORRECTED: GST and all pricing logic moved entirely to M2; M4 stores and forwards only

---

### M4-D-021 — B2B Webhook Delivery

| Field | Value |
|---|---|
| **Date** | 2026-05-11 |
| **Status** | DECIDED |
| **Source** | docs/M4/M4-ORDERS-DESIGN.md §7.6 |

**Decision:**  
B2B accounts can register a webhook URL. M4 delivers state-change events to this URL with an HMAC-SHA256 signature (`X-1DD-Signature` header). Retry policy: 5 attempts with exponential backoff. After 5 failures, webhook is suspended and ops alerted.

**Implications:**
- `webhook_url` and `webhook_secret` columns on `B2bAccount`
- `POST /api/v1/b2b/accounts/{id}/webhooks` endpoint for registration
- `WebhookDeliveryService` in M4 service layer
- Webhook delivery does not block or affect state transitions

**Change log:**  
_(First entry)_

---

## M5 — DA Assignment & Dispatch

_No design doc written yet. Decisions will be added when M5 design is authored._

### Placeholder Assumptions

| ID | Assumption | Status | Source |
|---|---|---|---|
| M5-D-001 | DA assignment objective function (A1–A4) is unresolved; blocks M5 implementation | OPEN | CLAUDE.md §Open Questions |
| M5-D-002 | Priority queue uses closer-first heuristic (geographic proximity to DA's last known GPS) | ASSUMED | MODULES.md M5 |
| M5-D-003 | Cron-meeting constraint is a hard constraint — no assignment if it breaks cron handoff | DECIDED | CLAUDE.md Key Design Invariants |
| M5-D-004 | DA operates across ~16 hours in 2 shifts; exact shift times TBD with ops | OPEN | MODULES.md M5 |
| M5-D-005 | No 3P carrier fallback if DA unavailable — station manager resolves manually | DECIDED | XC-D-009 |

---

## M6 — Van Routing & Scheduling

_No design doc written yet. Decisions will be added when M6 design is authored._

### Placeholder Assumptions

| ID | Assumption | Status | Source |
|---|---|---|---|
| M6-D-001 | Route graph uses grid vertices from M3 as stops | DECIDED | MODULES.md M6 |
| M6-D-002 | Nightly replan is the default; intraday changes require approval | DECIDED | XC-D-008 |
| M6-D-003 | Route optimiser uses 70/30 historical/current weighting | ASSUMED | XC-D-004 |
| M6-D-004 | Per-parcel cost floor from M2 is a first-class input to the optimiser | DECIDED | MODULES.md M6 |

---

## M7 — Hub Operations & Sortation

_No design doc written yet. Decisions will be added when M7 design is authored._

### Placeholder Assumptions

| ID | Assumption | Status | Source |
|---|---|---|---|
| M7-D-001 | Hub overload tactics (H1–H3) are unresolved; block full M7 implementation | OPEN | CLAUDE.md §Open Questions |
| M7-D-002 | Flight bag reschedule (low-weight rule) requires all parcels in bag to still meet SLA under new flight | DECIDED | MODULES.md M7 |
| M7-D-003 | Stand overflow triggers bag reassignment to alternate stand plus re-label workflow | DECIDED | MODULES.md M7 |
| M7-D-004 | System auto-generates per-bag per-flight manifest; no manual manifest | DECIDED | MODULES.md M7 |
| M7-D-005 | Destination hub uses symmetric sortation and stand logic as origin hub | DECIDED | MODULES.md M7 |

---

## M8 — Barcode, Label & Scanning

---

### M8-D-001 — Barcode Format: QR Code

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | Team discussion; docs/M8-BARCODE-DESIGN.md §4 |

**Decision:**  
Use **QR Code (2D)** for both parcel labels and bag labels.

**Rationale:**  
DA app uses phone cameras for scanning; QR is natively supported by all modern phones without dedicated hardware. Encodes more data than Code128.

**Risk flagged:**  
Airport/GHA scanners may expect IATA standard (DataMatrix or Code128). Validate with airline partners before go-live (see M8-D-012).

**Change log:**  
_(No changes yet)_

---

### M8-D-002 — Parcel ID Format

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | Team discussion; docs/M8-BARCODE-DESIGN.md §3 |

**Decision:**  
`{CITY_CODE}-{YYYYMMDD}-{6-digit-sequence}` — e.g. `BLR-20260510-000042`.  
- Date in IST (not UTC)  
- Sequence resets daily per city at midnight IST  
- Zero-padded to 6 digits (max 999,999/city/day; widen to 7 if needed)  
- Immutable once assigned

**Change log:**  
_(No changes yet)_

---

### M8-D-003 — Parcel ID Owner: M4 Generates, M8 Registers

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | docs/M8-BARCODE-DESIGN.md §3.3 |

**Decision:**  
M4 generates the parcel ID at booking time using a `parcel_id_counters` table. M4 calls `BarcodeService.registerParcel()` to inform M8. M8 does not generate IDs independently.

**Rationale:**  
The parcel ID is part of the booking record (M4's domain). M8's concern is scanning and labels, not ID generation.

**Change log:**  
_(No changes yet)_

---

### M8-D-004 — Scan Ledger Is Append-Only

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | CLAUDE.md Key Design Invariants; docs/M8-BARCODE-DESIGN.md §5.1 |

**Decision:**  
`scan_events` table rows are never updated or deleted. Corrections are new rows. `occurred_at` (device clock) and `received_at` (server clock) are both stored and immutable.

**Change log:**  
_(No changes yet)_

---

### M8-D-005 — Offline Scanning: Online-Only in v1

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | Team discussion; docs/M8-BARCODE-DESIGN.md §2 |

**Decision:**  
All scans require network connectivity in v1. DA app does not queue scans locally for later sync.

**Deferred:** Offline queue with reconnect sync is a known gap. When added, DA app stores scans locally with timestamps and replays on reconnect. The server-side API already uses `occurred_at` (device clock) which supports this.

**Change log:**  
_(No changes yet)_

---

### M8-D-006 — Scan Event Idempotency Strategy

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | OPEN |
| **Source** | docs/M8-BARCODE-DESIGN.md §5.4 |

**Options evaluated:**  
- **Option A:** Dedup at write by (parcel_id + scan_point + occurred_at). Risk: clock skew causes dropped scans.  
- **Option B:** Accept all, flag duplicates with `is_duplicate` boolean. Good for audit; does not drop scans.  
- **Option C:** Client-supplied UUID idempotency key. Clean ledger; no clock dependency. Recommended.

**Recommendation:** Option C. Schema already includes `idempotency_key VARCHAR(100) UNIQUE` column in design.

**Needs decision from team** before implementing scan ingestion endpoint.

**Change log:**  
_(No changes yet)_

---

### M8-D-007 — QR Error Correction Level

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | docs/M8-BARCODE-DESIGN.md §4.1 |

**Decision:**  
Error Correction Level **M** (15% data recovery). Balances data density against damage tolerance for labels on parcels in transit.

**Change log:**  
_(No changes yet)_

---

### M8-D-008 — QR Generation Library

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | docs/M8-BARCODE-DESIGN.md §10 |

**Decision:**  
**ZXing** (`com.google.zxing:core` + `com.google.zxing:javase`). Mature, well-maintained, Apache 2.0 licensed.

**Change log:**  
_(No changes yet)_

---

### M8-D-009 — QR Label Caching

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | docs/M8-BARCODE-DESIGN.md §10 |

**Decision:**  
Generated QR label PNG bytes are cached in **Redis** with key `qr:parcel:{parcel_id}` and TTL of **24 hours**. Labels are regenerated only on cache miss. Cache is invalidated if label data changes (e.g. stand reassignment updates bag label).

**Change log:**  
_(No changes yet)_

---

### M8-D-010 — Bag Label QR Size Threshold

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | docs/M8-BARCODE-DESIGN.md §4.2 |

**Decision:**  
Bag QR embeds the full `parcel_ids` array for bags with **≤ 100 parcels**. For bags with > 100 parcels, the array is omitted and replaced with a `manifest_url` reference to stay within QR data capacity limits.

**Change log:**  
_(No changes yet)_

---

### M8-D-011 — Scan Event Table Partitioning

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | docs/M8-BARCODE-DESIGN.md §8.1 |

**Decision:**  
`scan_events` table is partitioned by `occurred_at` month using PostgreSQL declarative partitioning. Implement from day one to avoid a costly migration later. Each month becomes a separate partition; old partitions can be archived without touching live data.

**Trigger:** Activate partitioning when table exceeds ~50M rows or query performance degrades.

**Change log:**  
_(No changes yet)_

---

### M8-D-012 — Airline Barcode Standard Compatibility

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | OPEN |
| **Source** | docs/M8-BARCODE-DESIGN.md §12, OD-M8-4 |

**Risk:**  
Airport / GHA scanners may expect **IATA standard** barcode format (commonly DataMatrix or Code128 per IATA Resolution 606). Our current choice of QR may not be scannable by airline partner hardware.

**Action required:**  
Validate barcode format with each airline partner before implementing bag labels. If IATA compliance is required, the bag label format must use DataMatrix. The `QrCodeGenerator` is designed as an interface so the implementation can be swapped.

**Change log:**  
_(No changes yet)_

---

### M8-D-013 — B2B Label Templates

| Field | Value |
|---|---|
| **Date** | 2026-05-10 |
| **Status** | DECIDED |
| **Source** | docs/M8-BARCODE-DESIGN.md §12, OD-M8-6 |

**Decision:**  
Per-B2B-account label customisation (branding, logo) is **out of scope for v1**. `LabelBuilder` is implemented as a strategy pattern (`LabelBuilderStrategy` interface) so custom templates can be plugged in later without changing the core.

**Change log:**  
_(No changes yet)_

---

## M9 — Airline & Flight Integration

_No design doc written yet. Decisions will be added when M9 design is authored._

### Placeholder Assumptions

| ID | Assumption | Status | Source |
|---|---|---|---|
| M9-D-001 | AWB issuer and handover SLA in minutes (L1–L3) are unresolved; block M9 implementation | OPEN | CLAUDE.md §Open Questions |
| M9-D-002 | If our cron is late: our responsibility; rebook on next flight | DECIDED | MODULES.md M9 |
| M9-D-003 | If airline processing blocks loading after we hand over: airline's responsibility; record reason code | DECIDED | MODULES.md M9 |
| M9-D-004 | Flight status is polled from airline/GHA APIs on a background schedule (frequency TBD) | ASSUMED | MODULES.md M9 |

---

## M10 — SLA Monitoring & Escalation

_No design doc written yet. Decisions will be added when M10 design is authored._

### Placeholder Assumptions

| ID | Assumption | Status | Source |
|---|---|---|---|
| M10-D-001 | SLA states: GREEN / AMBER / RED per leg | DECIDED | MODULES.md M10 |
| M10-D-002 | RED state requires a supervisor action, not just a notification | DECIDED | MODULES.md M10 |
| M10-D-003 | Hub-to-airport handover SLA threshold in minutes is TBD with ops | OPEN | CLAUDE.md §Open Questions |
| M10-D-004 | All SLA events and escalation actions are audit-logged | DECIDED | CLAUDE.md |

---

## M11 — Exception Handling, Call Center & RTO

_No design doc written yet. Decisions will be added when M11 design is authored._

### Placeholder Assumptions

| ID | Assumption | Status | Source |
|---|---|---|---|
| M11-D-001 | RTO attempt count N and days window are TBD with ops (F1–F3 unresolved) | OPEN | CLAUDE.md §Open Questions |
| M11-D-002 | DA non-attempt penalty flag is in scope; full payroll integration is out of scope v1 | DECIDED | MODULES.md M11 |
| M11-D-003 | Overnight failures are held in call center queue until call center opens | ASSUMED | MODULES.md M11 |
| M11-D-004 | Call center agent inputs reschedule window; system auto-schedules next attempt | DECIDED | MODULES.md M11 |

---

## Pending Business Decisions

Decisions that require a business call before the relevant feature can be implemented. These are not engineering open questions — the engineering design is ready to accommodate any reasonable answer.

---

### BD-001 — Cancellation Policy

| Field | Value |
|---|---|
| **Date** | 2026-05-12 |
| **Status** | OPEN |
| **Affects** | M4 cancellation API, M4 state machine, M11, refund flow |

**Current assumption:** Cancellation allowed in states `BOOKED`, `PICKUP_ASSIGNED`, `PICKED_UP`. After `PICKED_UP` it is rejected. This is a placeholder pending a business decision.

**Questions to decide:**
1. **Up to which state should cancellation be allowed?**
   - Option A: Up to `PICKED_UP` (current assumption) — safest; DA has the parcel
   - Option B: Up to `HANDED_TO_VAN` — allows cancellation until the hub journey begins
   - Option C: Up to `AT_ORIGIN_HUB` — possible if hub can intercept before sortation
   - Recommendation: Confirm with ops what is physically feasible to intercept

2. **Should we charge a cancellation fee?**
   - Option A: No fee if cancelled before DA is assigned (`BOOKED` state)
   - Option B: Flat fee if DA has already been dispatched (`PICKUP_ASSIGNED` or later)
   - Option C: Tiered fee based on how far along the shipment is
   - COD shipments: no payment to refund; fee collection mechanism TBD

3. **Different policies for B2C vs B2B vs C2C?**
   - B2B: cancellation may affect SLAs and invoicing; credit adjustment needed
   - C2C: likely same as B2C

**Engineering note:** The state machine transition table already supports any of these options — it's a configuration change, not a code architecture change.

---

### BD-002 — Payment Gateway Selection

| Field | Value |
|---|---|
| **Date** | 2026-05-12 |
| **Status** | OPEN |
| **Affects** | M4 payment integration, M4-D-002, M4-D-011 |

**Current assumption:** Razorpay for all PREPAID B2C and C2C payments.

**Questions to decide:**
1. **Which payment gateway should we use?**

| Gateway | Pros | Cons |
|---|---|---|
| **Razorpay** | India-first, strong UPI/COD support, good webhooks, wide adoption | Higher MDR on some instruments |
| **Cashfree** | Lower MDR, instant settlements, COD reconciliation support | Smaller developer ecosystem |
| **PayU** | Established, good enterprise contracts | Older API design |
| **Stripe** | Best API developer experience | Not India-optimised; UPI support limited |
| **PhonePe PG** | Strong UPI, India-first | Limited non-UPI support |

2. **COD reconciliation:** Does the chosen gateway support DA-side COD collection and reconciliation, or will we handle that separately through M5?

3. **Multi-gateway strategy:** Do we want a primary + fallback gateway for resilience, or single gateway in v1?

**Engineering note:** M4's `PaymentPort` interface abstracts the gateway. Switching or adding a gateway requires implementing a new `PaymentPort` adapter — no core M4 logic changes.

---

## Appendix: Open Decisions Tracker

Quick reference for all items currently in OPEN status that block or influence implementation.

| ID | Module | Blocked Implementation | Owner | Target Date | Status |
|---|---|---|---|---|---|
| BD-001 | M4 | Cancellation state boundary + fee policy | Business + ops | Before cancellation API impl | OPEN |
| BD-002 | M4 | Payment gateway selection | Business | Before payment integration | OPEN |
| M4-D-006 | M4 | State machine ops sign-off | Satvik + ops | Before M4 impl starts | OPEN |
| M4-D-012 | M4 | Refund flow edge cases (partial refund, delay SLA) | Satvik | Before cancellation API | OPEN |
| M8-D-006 | M8 | Scan idempotency strategy | Satvik | Before scan API impl | OPEN |
| M8-D-012 | M8 | Airline barcode standard validation | M9 team + ops | Before bag labels | OPEN |
| M3-D-001 | M3 | Grid vertex/tile rules (G1–G4) | M3 team | Blocks M3 entirely | OPEN |
| M5-D-001 | M5 | DA assignment objective function | M5 team | Blocks M5 entirely | OPEN |
| M7-D-001 | M7 | Hub overload tactics | M7 team | Blocks M7 entirely | OPEN |
| M9-D-001 | M9 | AWB issuer + handover SLA | M9 team + ops | Blocks M9 entirely | OPEN |
| M11-D-001 | M11 | RTO attempt count and window | M11 team + ops | Blocks M11 entirely | OPEN |
| **RESOLVED** | | | | | |
| M4-D-005 | M4 | ETA computation ownership | Satvik | 2026-05-11 | DECIDED (delegate to M9) |
| M4-D-007 | M4 | Multi-parcel B2B data model | Satvik | 2026-05-11 | DECIDED (1:1 in v1) |
