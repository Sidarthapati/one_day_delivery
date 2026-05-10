# M4 — Order Booking & Shipment Lifecycle: Design Document

**Version:** 0.1-draft  
**Author:** Satvik  
**Last updated:** 2026-05-10  
**Status:** Draft — open questions flagged inline

---

## 1. Purpose

M4 is the central module of the platform. It owns:
- The customer-facing booking API (B2B and B2C)
- The canonical shipment state machine
- The single source of truth for every shipment's current state
- ETA promises surfaced to customers
- Coordination of payment (Razorpay) and notification (SMS, Email, WhatsApp)

Every other module either feeds state into M4 (via Kafka events) or reads shipment data from M4. M4 never calls M5–M11 directly; it only emits events and reacts to theirs.

---

## 2. Scope

### In scope for v1
- B2C single-shipment booking with Razorpay prepayment
- B2B single-shipment booking with monthly invoice (credit terms)
- Full shipment state machine (BOOKED → DELIVERED or RTO)
- Cancellation up to and including the PICKED_UP state
- Customer tracking API (state + ETA)
- Notification dispatch on every state transition (SMS, Email, WhatsApp)
- Serviceability check at booking (delegates to M3)
- Price quote at booking (delegates to M2)
- Placeholder ETA at booking (rule-based, replaced when M9 is live)

### Out of scope for v1
- Bulk B2B booking (multiple shipments in one API call)
- Address modification after booking
- B2C payment refund flow (flagged as open — see §13)
- Delivery rescheduling (handled by M11)
- Reporting / invoice portal for B2B (later phase)

---

## 3. Domain Model

### 3.1 Core Entities

#### `Shipment`
The canonical record for one shipment end-to-end.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Internal PK |
| `shipment_ref` | VARCHAR(30) | Human-readable reference, e.g. `1DD-BLR-20260510-00042` |
| `customer_type` | ENUM(`B2C`, `B2B`) | Drives pricing and auth |
| `b2b_account_id` | UUID (nullable) | Null for B2C |
| `sender_name` | VARCHAR | |
| `sender_phone` | VARCHAR(15) | |
| `sender_email` | VARCHAR | |
| `origin_address` | JSONB | Full address object (see §3.3) |
| `origin_city` | VARCHAR(10) | City code, e.g. `BLR` |
| `origin_pincode` | VARCHAR(10) | |
| `dest_address` | JSONB | Full address object |
| `dest_city` | VARCHAR(10) | |
| `dest_pincode` | VARCHAR(10) | |
| `receiver_name` | VARCHAR | |
| `receiver_phone` | VARCHAR(15) | |
| `receiver_email` | VARCHAR (nullable) | |
| `weight_grams` | INTEGER | Actual weight |
| `length_cm` | SMALLINT | |
| `width_cm` | SMALLINT | |
| `height_cm` | SMALLINT | |
| `volumetric_weight_grams` | INTEGER | Computed at booking; stored for audit |
| `chargeable_weight_grams` | INTEGER | `max(actual, volumetric)` |
| `quoted_price_paise` | BIGINT | Price in smallest currency unit |
| `final_price_paise` | BIGINT (nullable) | Set after weight confirmation |
| `rate_card_version` | VARCHAR | Snapshot of pricing version used |
| `state` | ENUM | Current state (see §4) |
| `eta_promised` | TIMESTAMPTZ | ETA shown to customer at booking |
| `eta_updated` | TIMESTAMPTZ (nullable) | Latest revised ETA |
| `assigned_flight_id` | UUID (nullable) | Set by M9; nullable until assigned |
| `origin_tile_id` | UUID (nullable) | Set by M3 serviceability check |
| `parcel_id` | VARCHAR(30) (nullable) | Set by M8 at DA pickup scan |
| `payment_id` | UUID (nullable) | FK to `payment_transactions` |
| `cancelled_at` | TIMESTAMPTZ (nullable) | |
| `cancellation_reason` | VARCHAR (nullable) | |
| `city_id` | VARCHAR(10) | Origin city; used for city-scoped auth |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

#### `ShipmentStateHistory`
Append-only audit trail. Never updated or deleted.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `shipment_id` | UUID | FK to `shipments` |
| `from_state` | ENUM | Null for initial BOOKED entry |
| `to_state` | ENUM | |
| `triggered_by` | VARCHAR | Actor ID or system identifier |
| `trigger_source` | ENUM(`API`, `KAFKA_EVENT`, `SYSTEM`) | |
| `event_ref` | VARCHAR (nullable) | Kafka message key or API request ID |
| `notes` | TEXT (nullable) | Human-readable context |
| `occurred_at` | TIMESTAMPTZ | |

#### `PaymentTransaction`
One row per payment attempt. Linked to a shipment.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Internal PK |
| `shipment_id` | UUID | FK |
| `razorpay_order_id` | VARCHAR | Created by us on Razorpay |
| `razorpay_payment_id` | VARCHAR (nullable) | Set on capture |
| `razorpay_signature` | VARCHAR (nullable) | For webhook verification |
| `amount_paise` | BIGINT | |
| `currency` | VARCHAR(3) | `INR` |
| `status` | ENUM(`CREATED`, `AUTHORIZED`, `CAPTURED`, `FAILED`, `REFUNDED`) | |
| `payment_method` | VARCHAR (nullable) | card / upi / netbanking |
| `created_at` | TIMESTAMPTZ | |
| `updated_at` | TIMESTAMPTZ | |

#### `B2bAccount`
Represents a business customer with credit terms.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `account_name` | VARCHAR | |
| `gstin` | VARCHAR(15) | |
| `billing_email` | VARCHAR | |
| `credit_limit_paise` | BIGINT | |
| `payment_terms_days` | SMALLINT | e.g. 30 |
| `rate_card_id` | UUID | FK to M2's rate table |
| `city_id` | VARCHAR(10) | Primary city of operation |
| `is_active` | BOOLEAN | |
| `created_at` | TIMESTAMPTZ | |

### 3.2 Relationship: Order ↔ Parcel

> **Open Decision OD-1:** Whether one Order maps to one Parcel or N Parcels is not finalised.
>
> **Recommended approach:** Design the data model now as 1 Order = 1 Parcel (simplest, covers 100% of B2C and most B2B). When multi-parcel B2B is needed, introduce a `parcels` table as a child of `shipments` with a migration. Avoid premature complexity.
>
> This means `parcel_id` lives on the `Shipment` record in v1. If multi-parcel is added, `parcel_id` moves to a `Parcel` child entity.

### 3.3 Address Object (JSONB Schema)

```json
{
  "line1": "12, MG Road",
  "line2": "Near Brigade Junction",
  "landmark": "Opposite HDFC Bank",
  "city": "Bengaluru",
  "city_code": "BLR",
  "state": "Karnataka",
  "pincode": "560001",
  "latitude": 12.9716,
  "longitude": 77.5946
}
```

Stored as JSONB for flexibility. `city_code` and `pincode` are also promoted to top-level columns on `Shipment` for indexed queries.

---

## 4. State Machine

> **Status:** Draft based on MODULES.md. Requires sign-off from ops before implementation.

### 4.1 States

| State | Meaning |
|---|---|
| `BOOKED` | Shipment created, payment captured (B2C) or invoiced (B2B) |
| `PICKUP_ASSIGNED` | DA assigned by M5 |
| `PICKED_UP` | DA confirmed physical pickup, barcode attached (M8) |
| `AT_ORIGIN_HUB` | Scanned in at origin hub by M7/M8 |
| `IN_BAG` | Parcel bagged for a specific flight by M7 |
| `DEPARTED` | Bag departed airport (M9 flight event) |
| `IN_TRANSIT` | In air |
| `AT_DEST_HUB` | Scanned in at destination hub |
| `OUT_FOR_DELIVERY` | Last-mile DA assigned and en route |
| `DELIVERED` | Delivery confirmed by DA |
| `PICKUP_FAILED` | DA could not pick up (max attempts exceeded → M11) |
| `DELIVERY_FAILED` | DA could not deliver (max attempts exceeded → M11) |
| `RTO_INITIATED` | Return-to-origin triggered by M11 |
| `RTO_IN_TRANSIT` | Parcel on return flight |
| `RTO_COMPLETED` | Returned to sender |
| `CANCELLED` | Cancelled before PICKED_UP |

### 4.2 Allowed Transitions

```
BOOKED
  → PICKUP_ASSIGNED       (M5: da.assigned event)
  → CANCELLED             (API: customer cancels)

PICKUP_ASSIGNED
  → PICKED_UP             (M5: da.pickup_completed event)
  → PICKUP_FAILED         (M5: da.pickup_failed event)
  → CANCELLED             (API: customer cancels before pickup)

PICKED_UP
  → AT_ORIGIN_HUB         (M8: scan event at origin hub)

AT_ORIGIN_HUB
  → IN_BAG                (M7: bag creation event)

IN_BAG
  → DEPARTED              (M9: flight.departed event)

DEPARTED
  → IN_TRANSIT            (M9: flight.airborne event — or collapse with DEPARTED)

IN_TRANSIT
  → AT_DEST_HUB           (M9: flight.landed + M8: dest hub scan)

AT_DEST_HUB
  → OUT_FOR_DELIVERY      (M5: last-mile DA assigned)

OUT_FOR_DELIVERY
  → DELIVERED             (M5: da.delivery_completed event)
  → DELIVERY_FAILED       (M5: da.delivery_failed event)

DELIVERY_FAILED
  → RTO_INITIATED         (M11: after N failed attempts)
  → OUT_FOR_DELIVERY      (M11: rescheduled attempt)

PICKUP_FAILED
  → PICKUP_ASSIGNED       (M11: rescheduled attempt)
  → CANCELLED             (M11: no further attempts)

RTO_INITIATED
  → RTO_IN_TRANSIT        (reverse flight assigned by M9)

RTO_IN_TRANSIT
  → RTO_COMPLETED         (delivery to sender confirmed)
```

### 4.3 Illegal Transitions

Any transition not listed above is **rejected** by the service layer with a `409 Conflict`. The state machine is the single authority — no module can skip a state.

### 4.4 State Machine Implementation

Use a `ShipmentStateMachine` service class with an explicit transition table (a `Map<State, Set<State>>`). Every transition is validated before write, and the result is written atomically with a `ShipmentStateHistory` row in the same DB transaction.

```java
// Pseudocode — not production code
public void transition(UUID shipmentId, ShipmentState targetState, TransitionContext ctx) {
    Shipment s = repository.findByIdWithLock(shipmentId);  // SELECT FOR UPDATE
    if (!ALLOWED_TRANSITIONS.get(s.getState()).contains(targetState)) {
        throw new IllegalStateTransitionException(s.getState(), targetState);
    }
    ShipmentState prev = s.getState();
    s.setState(targetState);
    repository.save(s);
    historyRepository.save(ShipmentStateHistory.of(s.getId(), prev, targetState, ctx));
}
```

---

## 5. API Design

### 5.1 B2C Endpoints

#### `POST /api/v1/b2c/shipments` — Book a shipment

**Auth:** JWT with role `B2C_CUSTOMER`

**Request:**
```json
{
  "sender": {
    "name": "Priya Sharma",
    "phone": "+919876543210",
    "email": "priya@example.com",
    "address": {
      "line1": "12 MG Road",
      "city_code": "BLR",
      "pincode": "560001"
    }
  },
  "receiver": {
    "name": "Rahul Mehta",
    "phone": "+919999988888",
    "address": {
      "line1": "45 Marine Lines",
      "city_code": "BOM",
      "pincode": "400002"
    }
  },
  "parcel": {
    "weight_grams": 1200,
    "length_cm": 30,
    "width_cm": 20,
    "height_cm": 15,
    "description": "Electronics"
  },
  "razorpay_payment_id": "pay_XXXXXXXXXXXXXXXX",
  "razorpay_order_id": "order_XXXXXXXXXXXXXXXX",
  "razorpay_signature": "abc123..."
}
```

**Flow:**
1. Validate sender/receiver pincodes via M3 serviceability check
2. Compute volumetric weight; select chargeable weight
3. Get price quote from M2
4. Verify Razorpay payment signature; capture payment
5. Create `Shipment` in state `BOOKED`
6. Write initial `ShipmentStateHistory` row
7. Emit `shipment.created` Kafka event
8. Trigger notification (SMS + Email + WhatsApp) asynchronously
9. Return shipment reference and ETA

**Response `201 Created`:**
```json
{
  "shipment_ref": "1DD-BLR-20260510-00042",
  "state": "BOOKED",
  "quoted_price": {
    "amount": 450.00,
    "currency": "INR",
    "breakdown": {
      "base_rate": 400.00,
      "weight_surcharge": 50.00
    }
  },
  "eta_promised": "2026-05-11T20:00:00+05:30",
  "tracking_url": "/api/v1/shipments/1DD-BLR-20260510-00042/track",
  "payment": {
    "status": "CAPTURED",
    "razorpay_payment_id": "pay_XXXXXXXXXXXXXXXX"
  }
}
```

**Error responses:**
| Code | Scenario |
|---|---|
| `400` | Missing required fields, invalid pincode format |
| `422` | Origin or destination pincode not serviceable |
| `402` | Payment verification failed |
| `409` | Duplicate Razorpay payment ID |

---

#### `POST /api/v1/b2c/shipments/quote` — Get price quote without booking

**Auth:** None (public endpoint) or JWT

**Request:** Same as booking minus payment fields.

**Response `200 OK`:**
```json
{
  "quoted_price": { "amount": 450.00, "currency": "INR" },
  "chargeable_weight_grams": 1800,
  "eta_window": "Next day delivery if booked before 10:00 AM IST",
  "serviceable": true
}
```

---

#### `DELETE /api/v1/b2c/shipments/{ref}` — Cancel shipment

**Auth:** JWT with role `B2C_CUSTOMER` (must own the shipment)

**Business rules:**
- Allowed only in states: `BOOKED`, `PICKUP_ASSIGNED`, `PICKED_UP`
- After `PICKED_UP`, cancellation is rejected with `409`

**Response `200 OK`:**
```json
{
  "shipment_ref": "1DD-BLR-20260510-00042",
  "state": "CANCELLED",
  "refund": {
    "status": "INITIATED",
    "estimated_days": 5,
    "razorpay_refund_id": "rfnd_XXXXXXXXXXXXXXXX"
  }
}
```

> **Open Decision OD-2:** Refund flow via Razorpay is not fully specified. Refund should be initiated synchronously; Razorpay webhooks will confirm completion. Map to a `refund_status` field on `PaymentTransaction`.

---

#### `GET /api/v1/shipments/{ref}/track` — Public tracking

**Auth:** None (public)

**Response `200 OK`:**
```json
{
  "shipment_ref": "1DD-BLR-20260510-00042",
  "state": "AT_ORIGIN_HUB",
  "state_label": "Parcel received at Bengaluru hub",
  "eta_promised": "2026-05-11T20:00:00+05:30",
  "eta_updated": null,
  "timeline": [
    { "state": "BOOKED", "label": "Order confirmed", "occurred_at": "2026-05-10T09:30:00+05:30" },
    { "state": "PICKUP_ASSIGNED", "label": "Delivery associate assigned", "occurred_at": "2026-05-10T09:45:00+05:30" },
    { "state": "PICKED_UP", "label": "Parcel picked up", "occurred_at": "2026-05-10T11:20:00+05:30" },
    { "state": "AT_ORIGIN_HUB", "label": "Arrived at Bengaluru hub", "occurred_at": "2026-05-10T13:05:00+05:30" }
  ]
}
```

---

### 5.2 B2B Endpoints

B2B uses separate endpoints. Auth is via API key (machine-to-machine) for automated integrations or JWT for the portal.

#### `POST /api/v1/b2b/shipments` — Book a single B2B shipment

**Auth:** `X-Api-Key` header (B2B API key) or JWT with role `B2B_BUSINESS_USER`

**Request:** Same structure as B2C, minus Razorpay fields. Additional fields:
```json
{
  "b2b_account_id": "acct_uuid_here",
  "purchase_order_ref": "PO-2026-XYZ",
  "declared_value_paise": 500000
}
```

**Flow:** Same as B2C except:
- No Razorpay; price is logged against the account's monthly invoice
- Credit limit is checked; if exceeded, booking is rejected with `402`
- Rate card used is the account-specific negotiated rate from M2

**Response:** Same shape as B2C, without payment block.

---

#### `GET /api/v1/b2b/shipments` — List shipments for an account

**Auth:** B2B API key or portal JWT

**Query params:** `from_date`, `to_date`, `state`, `page`, `page_size`

**Response:** Paginated list of shipment summaries.

---

### 5.3 Internal / Inter-module Endpoints

These are consumed only by other modules, not by external clients.

#### `PATCH /internal/v1/shipments/{id}/state` — State transition

Used by modules that cannot emit Kafka (for synchronous flows only). Prefer Kafka event consumption where possible.

**Auth:** Internal service token (not customer-facing)

```json
{
  "target_state": "AT_ORIGIN_HUB",
  "trigger_source": "KAFKA_EVENT",
  "event_ref": "kafka-msg-key-abc123",
  "notes": "Hub in-scan confirmed"
}
```

---

#### `GET /internal/v1/shipments/{id}` — Full shipment record

Used by M5, M10, M11 to fetch full context.

---

## 6. Service Layer Design

### 6.1 Package Structure

```
com.oneday.orders/
  api/
    B2cShipmentController.java
    B2bShipmentController.java
    TrackingController.java
    InternalShipmentController.java
  service/
    ShipmentService.java              (interface — public)
    impl/
      ShipmentServiceImpl.java        (package-private)
      ShipmentStateMachine.java       (package-private)
      EtaService.java                 (package-private)
      CancellationService.java        (package-private)
  domain/
    Shipment.java
    ShipmentState.java
    ShipmentStateHistory.java
    PaymentTransaction.java
    B2bAccount.java
  repository/
    ShipmentRepository.java
    ShipmentStateHistoryRepository.java
    PaymentTransactionRepository.java
    B2bAccountRepository.java
  events/
    ShipmentEventProducer.java        (Kafka producer)
    ShipmentEventConsumer.java        (Kafka consumer — listens to M5–M11 events)
  dto/
    BookingRequest.java
    BookingResponse.java
    TrackingResponse.java
    QuoteRequest.java
    QuoteResponse.java
```

### 6.2 External Module Contracts

M4 calls these interfaces. Implementations come from other modules at runtime (Spring wires them). Stub implementations live in M4's test sources for isolated testing.

```java
// M3 contract
public interface ServiceabilityPort {
    ServiceabilityResult check(String cityCode, String pincode);
}

// M2 contract
public interface PricingPort {
    QuoteResult computeQuote(QuoteRequest request);
}

// M9 contract (placeholder until M9 is live)
public interface EtaPort {
    EtaResult predictEta(String originCity, String destCity, Instant bookedAt);
}

// Notification (internal utility, not a module)
public interface NotificationPort {
    void send(NotificationRequest request);
}

// Payment
public interface PaymentPort {
    PaymentVerificationResult verify(String orderId, String paymentId, String signature);
    RefundResult initiateRefund(String paymentId, long amountPaise);
}
```

### 6.3 ETA Strategy

> **Open Decision OD-3:** ETA logic is a placeholder in v1.

Design: Use a `EtaPort` interface. V1 implementation returns a simple rule:
- If booked before **10:00 AM IST** → ETA = same day 8:00 PM at destination
- If booked after 10:00 AM IST → ETA = next day 8:00 PM at destination

When M9 is live, swap the implementation to pull the predicted flight departure + arrival + ground buffer. No M4 code changes needed.

---

## 7. Kafka Event Schema

### 7.1 Events Produced by M4

#### `shipment.created`
**Topic:** `oneday.shipments.created`  
**Key:** `shipment_id`

```json
{
  "event_id": "uuid",
  "occurred_at": "2026-05-10T09:30:00Z",
  "shipment_id": "uuid",
  "shipment_ref": "1DD-BLR-20260510-00042",
  "customer_type": "B2C",
  "origin_city": "BLR",
  "origin_pincode": "560001",
  "origin_tile_id": "uuid",
  "dest_city": "BOM",
  "dest_pincode": "400002",
  "chargeable_weight_grams": 1800,
  "eta_promised": "2026-05-11T14:30:00Z",
  "receiver_phone": "+919999988888"
}
```

**Consumers:** M5 (DA assignment), M8 (label prep), M10 (SLA tracking start)

---

#### `shipment.state_changed`
**Topic:** `oneday.shipments.state_changed`  
**Key:** `shipment_id`

```json
{
  "event_id": "uuid",
  "occurred_at": "2026-05-10T11:20:00Z",
  "shipment_id": "uuid",
  "shipment_ref": "1DD-BLR-20260510-00042",
  "from_state": "PICKUP_ASSIGNED",
  "to_state": "PICKED_UP",
  "triggered_by": "da_actor_id",
  "eta_updated": null
}
```

**Consumers:** M10 (SLA), M11 (exception checks), notification system

---

#### `shipment.cancelled`
**Topic:** `oneday.shipments.cancelled`  
**Key:** `shipment_id`

```json
{
  "event_id": "uuid",
  "occurred_at": "2026-05-10T10:00:00Z",
  "shipment_id": "uuid",
  "shipment_ref": "1DD-BLR-20260510-00042",
  "cancelled_at_state": "PICKUP_ASSIGNED",
  "reason": "Customer requested",
  "refund_initiated": true
}
```

**Consumers:** M5 (remove from DA queue), M10 (close SLA tracking)

---

### 7.2 Events Consumed by M4

M4's `ShipmentEventConsumer` listens to these topics and calls `ShipmentStateMachine.transition()` on each:

| Topic | Source | State Transition |
|---|---|---|
| `oneday.da.assigned` | M5 | BOOKED → PICKUP_ASSIGNED |
| `oneday.da.pickup_completed` | M5 | PICKUP_ASSIGNED → PICKED_UP |
| `oneday.da.pickup_failed` | M5 | PICKUP_ASSIGNED → PICKUP_FAILED |
| `oneday.scan.hub_in` | M8 | PICKED_UP → AT_ORIGIN_HUB |
| `oneday.hub.bag_created` | M7 | AT_ORIGIN_HUB → IN_BAG |
| `oneday.flight.departed` | M9 | IN_BAG → DEPARTED |
| `oneday.flight.landed` | M9 | DEPARTED / IN_TRANSIT → AT_DEST_HUB |
| `oneday.da.delivery_completed` | M5 | OUT_FOR_DELIVERY → DELIVERED |
| `oneday.da.delivery_failed` | M5 | OUT_FOR_DELIVERY → DELIVERY_FAILED |
| `oneday.rto.initiated` | M11 | DELIVERY_FAILED → RTO_INITIATED |
| `oneday.rto.completed` | M11 | RTO_IN_TRANSIT → RTO_COMPLETED |

**Consumer group:** `m4-shipment-state-consumer`  
**Error handling:** Dead-letter topic `oneday.shipments.dlq` for events that fail after 3 retries.

---

## 8. Database Schema

### 8.1 Flyway Migration: `V1__create_shipments.sql`

```sql
CREATE TYPE shipment_state AS ENUM (
  'BOOKED', 'PICKUP_ASSIGNED', 'PICKED_UP',
  'AT_ORIGIN_HUB', 'IN_BAG', 'DEPARTED', 'IN_TRANSIT',
  'AT_DEST_HUB', 'OUT_FOR_DELIVERY', 'DELIVERED',
  'PICKUP_FAILED', 'DELIVERY_FAILED',
  'RTO_INITIATED', 'RTO_IN_TRANSIT', 'RTO_COMPLETED',
  'CANCELLED'
);

CREATE TYPE customer_type AS ENUM ('B2C', 'B2B');

CREATE TABLE shipments (
  id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  shipment_ref             VARCHAR(30) NOT NULL UNIQUE,
  customer_type            customer_type NOT NULL,
  b2b_account_id           UUID,
  sender_name              VARCHAR(100) NOT NULL,
  sender_phone             VARCHAR(15) NOT NULL,
  sender_email             VARCHAR(254),
  origin_address           JSONB NOT NULL,
  origin_city              VARCHAR(10) NOT NULL,
  origin_pincode           VARCHAR(10) NOT NULL,
  dest_address             JSONB NOT NULL,
  dest_city                VARCHAR(10) NOT NULL,
  dest_pincode             VARCHAR(10) NOT NULL,
  receiver_name            VARCHAR(100) NOT NULL,
  receiver_phone           VARCHAR(15) NOT NULL,
  receiver_email           VARCHAR(254),
  weight_grams             INTEGER NOT NULL CHECK (weight_grams > 0),
  length_cm                SMALLINT NOT NULL,
  width_cm                 SMALLINT NOT NULL,
  height_cm                SMALLINT NOT NULL,
  volumetric_weight_grams  INTEGER NOT NULL,
  chargeable_weight_grams  INTEGER NOT NULL,
  quoted_price_paise       BIGINT NOT NULL,
  final_price_paise        BIGINT,
  rate_card_version        VARCHAR(50) NOT NULL,
  state                    shipment_state NOT NULL DEFAULT 'BOOKED',
  eta_promised             TIMESTAMPTZ NOT NULL,
  eta_updated              TIMESTAMPTZ,
  assigned_flight_id       UUID,
  origin_tile_id           UUID,
  parcel_id                VARCHAR(30),
  payment_id               UUID,
  cancelled_at             TIMESTAMPTZ,
  cancellation_reason      VARCHAR(500),
  city_id                  VARCHAR(10) NOT NULL,
  created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shipments_state         ON shipments(state);
CREATE INDEX idx_shipments_origin_city   ON shipments(origin_city);
CREATE INDEX idx_shipments_dest_city     ON shipments(dest_city);
CREATE INDEX idx_shipments_b2b_account   ON shipments(b2b_account_id) WHERE b2b_account_id IS NOT NULL;
CREATE INDEX idx_shipments_parcel_id     ON shipments(parcel_id) WHERE parcel_id IS NOT NULL;
CREATE INDEX idx_shipments_created_at    ON shipments(created_at);
CREATE INDEX idx_shipments_city_state    ON shipments(city_id, state);

CREATE TABLE shipment_state_history (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  shipment_id    UUID NOT NULL REFERENCES shipments(id),
  from_state     shipment_state,
  to_state       shipment_state NOT NULL,
  triggered_by   VARCHAR(100) NOT NULL,
  trigger_source VARCHAR(20) NOT NULL,
  event_ref      VARCHAR(200),
  notes          TEXT,
  occurred_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_history_shipment_id ON shipment_state_history(shipment_id);
CREATE INDEX idx_history_occurred_at ON shipment_state_history(occurred_at);

CREATE TABLE payment_transactions (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  shipment_id          UUID NOT NULL REFERENCES shipments(id),
  razorpay_order_id    VARCHAR(100) NOT NULL UNIQUE,
  razorpay_payment_id  VARCHAR(100),
  razorpay_signature   VARCHAR(500),
  amount_paise         BIGINT NOT NULL,
  currency             VARCHAR(3) NOT NULL DEFAULT 'INR',
  status               VARCHAR(20) NOT NULL,
  payment_method       VARCHAR(50),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE b2b_accounts (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_name         VARCHAR(200) NOT NULL,
  gstin                VARCHAR(15),
  billing_email        VARCHAR(254) NOT NULL,
  credit_limit_paise   BIGINT NOT NULL DEFAULT 0,
  payment_terms_days   SMALLINT NOT NULL DEFAULT 30,
  rate_card_id         UUID,
  city_id              VARCHAR(10) NOT NULL,
  is_active            BOOLEAN NOT NULL DEFAULT TRUE,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## 9. Payment Integration (Razorpay)

### 9.1 B2C Flow

```
1. Client calls POST /b2c/shipments/quote → gets amount
2. Client creates Razorpay order client-side (or calls our endpoint)
3. Customer pays on Razorpay checkout
4. Client calls POST /b2c/shipments with {razorpay_order_id, razorpay_payment_id, razorpay_signature}
5. M4 verifies HMAC-SHA256 signature server-side
6. On valid signature: capture payment via Razorpay API
7. On success: create Shipment, emit event
8. On failure: return 402
```

### 9.2 Razorpay Webhook Handler

`POST /webhooks/razorpay` — handles async events from Razorpay:

| Event | Action |
|---|---|
| `payment.captured` | Confirm `PaymentTransaction` status |
| `payment.failed` | Mark transaction as FAILED; cancel shipment |
| `refund.processed` | Mark refund complete; notify customer |

Webhook payloads are verified using Razorpay webhook secret (HMAC-SHA256).

### 9.3 B2B Flow

No Razorpay. Amount is logged against the B2B account. A separate billing service (out of scope for v1) handles monthly invoice generation. M4 checks `credit_limit_paise` against outstanding balance before accepting a booking.

---

## 10. Notification Design

All notifications are dispatched **asynchronously** via a `NotificationPort`. M4 publishes a `notification.requested` internal event; a dedicated notification service (or background thread) handles delivery.

### 10.1 Notification Triggers

| State Transition | SMS | Email | WhatsApp |
|---|---|---|---|
| BOOKED | Booking confirmation + tracking link | Full confirmation + invoice | Booking summary |
| PICKUP_ASSIGNED | DA name + ETA | — | DA assigned |
| PICKED_UP | Parcel collected | — | Parcel collected |
| AT_ORIGIN_HUB | At hub | — | — |
| DEPARTED | In transit | — | In transit |
| AT_DEST_HUB | At destination hub | — | — |
| OUT_FOR_DELIVERY | Out for delivery + DA name | — | OFD + ETA |
| DELIVERED | Delivered confirmation | Delivery confirmation | Delivered |
| DELIVERY_FAILED | Failed attempt + reschedule link | — | Failed + reschedule |
| CANCELLED | Cancellation confirmed | Cancellation + refund info | — |

### 10.2 Channels

| Channel | Provider (recommended) | Notes |
|---|---|---|
| SMS | MSG91 or Twilio | MSG91 has better India DLT compliance |
| Email | AWS SES | Cost-effective at scale |
| WhatsApp | Twilio WhatsApp Business API or Meta direct | Requires WhatsApp Business verification |

---

## 11. Shipment Reference Format

Human-readable reference: `1DD-{CITY}-{YYYYMMDD}-{5-digit-seq}`

Example: `1DD-BLR-20260510-00042`

- `1DD` — platform prefix
- `CITY` — origin city code (BLR, BOM, DEL, HYD, MAA)
- `YYYYMMDD` — booking date in IST
- `00042` — zero-padded daily sequence per city (resets at midnight IST)

**Generation:** A DB sequence per city per day, managed in M4. Sequence lives in a `shipment_ref_counters` table:

```sql
CREATE TABLE shipment_ref_counters (
  city_code   VARCHAR(10) NOT NULL,
  date_key    DATE NOT NULL,
  next_val    INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (city_code, date_key)
);
```

Use `SELECT ... FOR UPDATE` to increment atomically. If daily volume exceeds 99,999, widen to 6 digits.

---

## 12. Non-Functional Requirements

| NFR | Target | Implementation note |
|---|---|---|
| Booking API latency (p99) | < 2s | M2 and M3 calls are synchronous; stub both with < 100ms timeout in dev |
| Tracking API latency (p99) | < 200ms | Read from DB; add Redis cache on `shipment_ref` lookup if needed |
| State transition throughput | 10,000 events/min | Kafka consumer group; scale consumers horizontally |
| Uptime | 99.9% | Stateless service layer; DB is the source of truth |
| Data retention | 7 years | Shipment records and history never deleted; soft-archive after 2 years |
| City-scoped auth | All writes | `city_id` on every shipment; station manager JWT claims enforced in service layer |

---

## 13. Open Decisions

| ID | Decision | Impact | Recommended default |
|---|---|---|---|
| OD-1 | 1 Order = 1 or N Parcels | Data model, M8 integration | Start with 1:1; migrate to 1:N if B2B multi-parcel needed |
| OD-2 | B2C refund flow via Razorpay | Cancellation API | Initiate refund synchronously; confirm via webhook |
| OD-3 | ETA logic (placeholder vs M9-driven) | Customer promise accuracy | Placeholder in v1; interface ready for M9 swap |
| OD-4 | State machine sign-off with ops | Entire M4 | Schedule review with ops before implementation |
| OD-5 | Booking cutoff time per city | ETA calculation | Use 10:00 AM IST as default; make it config-driven |
| OD-6 | Razorpay order creation (client vs server) | B2C checkout flow | Server creates Razorpay order; client renders checkout |

---

## 14. Interface Contracts for Other Teams

M4 publishes the following contracts that other modules must respect:

**Events M4 guarantees to produce** (other modules may depend on):
- `oneday.shipments.created` — always on successful booking
- `oneday.shipments.state_changed` — on every state transition
- `oneday.shipments.cancelled` — on every cancellation

**APIs other modules may call:**
- `GET /internal/v1/shipments/{id}` — full shipment record
- `PATCH /internal/v1/shipments/{id}/state` — state transition (prefer Kafka)

**Shipment ID format:** UUID internally. `shipment_ref` (human-readable) for external/API use.