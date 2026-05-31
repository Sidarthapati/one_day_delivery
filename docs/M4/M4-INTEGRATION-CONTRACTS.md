# M4 — Integration Contracts

| Field | Value |
|---|---|
| **Module** | M4 — Orders |
| **Author** | Satvik |
| **Last updated** | 2026-05-31 |
| **Status** | Authoritative — must be kept in sync with M4-ORDERS-DESIGN.md |

This document is the single reference for every other module team integrating with M4. It covers exactly what M4 needs from you, what M4 gives you, and the precise contracts (types, fields, topics, endpoints) for both directions.

---

## Table of Contents

1. [What M4 Needs From Other Modules (Inbound)](#1-what-m4-needs-from-other-modules-inbound)
   - 1.1 [M3 — Serviceability Check (Sync Port)](#11-m3--serviceability-check-sync-port)
   - 1.2 [M2 — Pricing Quote (Sync Port)](#12-m2--pricing-quote-sync-port)
   - 1.3 [M9 — ETA Calculation (Sync Port)](#13-m9--eta-calculation-sync-port)
   - 1.4 [Notification Service — Send Notification (Async Port)](#14-notification-service--send-notification-async-port)
   - 1.5 [M5 — DA Events (Kafka)](#15-m5--da-events-kafka)
   - 1.6 [M7 — Hub Events (Kafka)](#16-m7--hub-events-kafka)
   - 1.7 [M8 — Scan Events (Kafka)](#17-m8--scan-events-kafka)
   - 1.8 [M9 — Flight Events (Kafka)](#18-m9--flight-events-kafka)
   - 1.9 [M6 — Cron Events (Kafka)](#19-m6--cron-events-kafka)
   - 1.10 [M11 — Exception Events (Kafka)](#110-m11--exception-events-kafka)
2. [What M4 Exposes to Other Modules (Outbound)](#2-what-m4-exposes-to-other-modules-outbound)
   - 2.1 [Kafka Events Produced by M4](#21-kafka-events-produced-by-m4)
   - 2.2 [Internal REST APIs](#22-internal-rest-apis)
3. [Shared Types Reference](#3-shared-types-reference)
   - 3.1 [ShipmentState Enum (27 values)](#31-shipmentstate-enum-27-values)
   - 3.2 [Other Domain Enums](#32-other-domain-enums)
   - 3.3 [Kafka Topics](#33-kafka-topics)
4. [Event Routing: What Triggers What](#4-event-routing-what-triggers-what)
5. [Critical Rules All Teams Must Follow](#5-critical-rules-all-teams-must-follow)
6. [Open Decisions Blocking Integration](#6-open-decisions-blocking-integration)

---

## 1. What M4 Needs From Other Modules (Inbound)

M4 integrates with other modules in two ways:
- **Synchronous port interfaces** — M4 calls these in-process during the booking request. Latency directly impacts booking response time. Failures may block the booking.
- **Kafka event consumption** — M4 subscribes to topics produced by other modules and drives its state machine from them. These are fully decoupled and async.

---

### 1.1 M3 — Serviceability Check (Sync Port)

**Implemented by:** M3 (grid module)  
**Called at:** Booking time, once per booking, synchronously  
**Circuit-broken:** Yes — M3 unavailability fails the booking  
**Latency budget:** < 500ms (booking must complete sub-2s end-to-end)

**Interface (already in `common`):**

```java
// com.oneday.common.port.ServiceabilityPort
public interface ServiceabilityPort {
    ServiceabilityResult check(String originPincode, String destPincode);
}
```

**Input:**

| Parameter | Type | Description |
|---|---|---|
| `originPincode` | `String` | Sender's pincode — as entered by customer at booking |
| `destPincode` | `String` | Receiver's pincode — as entered by customer at booking |

**Output (`ServiceabilityResult` record in `common/port/dto/`):**

```java
public record ServiceabilityResult(
    boolean serviceable,   // false if either pincode is outside M3's grid coverage
    UUID originTileId,     // grid tile of origin; M4 stores this on Shipment for M5 DA assignment
    DeliveryType deliveryType  // INTERCITY if different cities, SAME_CITY if same
)
```

**Rules M3 must follow:**
- Return `serviceable=false` for unsupported pincodes — **do not throw an exception**. M4 maps this to a `422 PINCODE_NOT_SERVICEABLE` response.
- `originTileId` must be non-null when `serviceable=true`. M4 stores it on the `Shipment` record; M5 reads it for DA assignment. A null tile ID when serviceable=true will cause M5 to fail assignment.
- `deliveryType` must be non-null when `serviceable=true`. M4 uses it to determine which state machine path applies (INTERCITY skips states 8–14 for SAME_CITY).
- M3 owns all city boundary logic — M4 never passes a cityCode; it only passes raw pincodes.

---

### 1.2 M2 — Pricing Quote (Sync Port)

**Implemented by:** M2 (pricing module)  
**Called at:** Booking time, once per booking, synchronously, after serviceability check passes  
**Circuit-broken:** Yes — M2 unavailability fails the booking  
**Latency budget:** < 500ms

**Interface (already in `common`):**

```java
// com.oneday.common.port.PricingPort
public interface PricingPort {
    QuoteResult computeQuote(QuoteRequest request);
}
```

**Input (`QuoteRequest` record in `common/port/dto/`):**

```java
public record QuoteRequest(
    CustomerType customerType,      // B2C, B2B, or C2C — selects the rate card family
    DeliveryType deliveryType,      // INTERCITY or SAME_CITY — from ServiceabilityResult
    String originCity,              // city code, e.g. "BLR"
    String destCity,                // city code, e.g. "DEL"
    int chargeableWeightGrams,      // max(actualWeight, volumetricWeight) — M4 computes this before calling
    Long declaredValuePaise,        // shipper-declared value; nullable; no pricing effect in v1
    UUID b2bRateCardId              // account-specific rate card; null for B2C and C2C
)
```

**M4 computes `chargeableWeightGrams` itself** before this call:
```
volumetricWeightGrams = (length_cm × width_cm × height_cm) / 5
chargeableWeightGrams = max(actualWeightGrams, volumetricWeightGrams)
```

**Output (`QuoteResult` record in `common/port/dto/`):**

```java
public record QuoteResult(
    long baseAmountPaise,           // freight charge before tax
    long taxPaise,                  // GST 18% in v1; M2 owns the rate
    long totalPricePaise,           // baseAmountPaise + taxPaise
    Map<String, Long> breakdown,    // e.g. {"base_freight": 4000, "fuel_surcharge": 500}
    String rateCardVersion          // snapshot of which rate card was applied; stored for audit
)
```

**Rules M2 must follow:**
- `totalPricePaise` must equal `baseAmountPaise + taxPaise` exactly. M4 stores all three independently.
- `rateCardVersion` must be non-null. M4 stores it on `Shipment.rate_card_version` for billing reconciliation.
- M4 forwards the entire result to the customer unchanged — it does not validate, adjust, or recompute any field.
- Throw a runtime exception on pricing failure (unsupported city pair, disabled rate card, etc.) — M4's circuit breaker will catch it and fail the booking with `503`.

---

### 1.3 M9 — ETA Calculation (Sync Port)

**Implemented by:** M9 (airline module)  
**Called at:** Two points in the shipment lifecycle (see below)  
**Circuit-broken:** No — ETA failure does not block state transitions. M4 proceeds with null ETA and logs a warning.

**Interface (already in `common`):**

```java
// com.oneday.common.port.EtaPort
public interface EtaPort {
    EtaResult fetchEta(EtaRequest request);
}
```

**Input (`EtaRequest` + `EtaContext` records in `common/port/dto/`):**

```java
public record EtaRequest(
    UUID shipmentId,
    ShipmentState currentState,   // the state M4 is currently entering; M9 uses this to select its model
    Instant occurredAt,           // when this state was entered; use this, not now(), for accurate ETA
    EtaContext context
)

public record EtaContext(
    String originCity,            // e.g. "BLR"
    String destCity,              // e.g. "DEL"
    DeliveryType deliveryType,    // INTERCITY or SAME_CITY
    Instant bookedAt,             // when the shipment was created; M9 uses this to find next feasible flight
    UUID assignedFlightId         // null at BOOKED; populated once M9 has assigned a flight
)
```

**Output (`EtaResult` record in `common/port/dto/`):**

```java
public record EtaResult(
    Instant etaPromised,          // absolute timestamp of expected delivery; M4 stores + sends to customer
    int slaCommitmentMinutes      // total minutes committed; stored on Shipment.sla_commitment_minutes
)
```

**When M4 calls EtaPort:**

| Call point | `currentState` | What M4 does with result |
|---|---|---|
| After booking | `BOOKED` | Stores as `eta_promised`; included in booking response to customer |
| On `AT_ORIGIN_HUB` (both DA_PICKUP and SELF_DROP paths) | `AT_ORIGIN_HUB` | Stores as `eta_updated`; triggers customer notification with updated ETA |

**Rules M9 must follow:**
- All ETA logic, edge cases, and fallbacks are M9's sole responsibility. M4 stores whatever M9 returns.
- If M9 cannot compute an ETA, return `null` fields rather than throwing — M4 treats nulls as "ETA unavailable" and does not fail the state transition.
- `occurredAt` in `EtaRequest` may lag behind wall-clock time due to Kafka processing delay. Always use `occurredAt`, not `Instant.now()`, as the reference point.
- `assignedFlightId` will be null on the `BOOKED` call; M9 must handle this gracefully with a best-estimate.

---

### 1.4 Notification Service — Send Notification (Async Port)

**Implemented by:** Notification service (separate service, not a numbered module)  
**Called at:** Multiple state transitions — OTP generation, state changes  
**Circuit-broken:** No — M4 publishes to Kafka topic `oneday.notifications.requested` and returns immediately. Notification failures do not affect shipment state.

**Interface (already in `common`):**

```java
// com.oneday.common.port.NotificationPort
public interface NotificationPort {
    void send(NotificationRequest request);
}
```

**Input (`NotificationRequest` record in `common/port/dto/`):**

```java
public record NotificationRequest(
    NotificationEventType type,   // OTP_GENERATED or STATE_CHANGED
    String recipientPhone,        // E.164 format, e.g. "+919876543210"
    String recipientEmail,        // nullable; not all customers provide email
    String shipmentRef,           // human-readable, e.g. "1DD-BLR-20260519-000042"
    ShipmentState newState,       // the state just entered; null for OTP_GENERATED
    String otp,                   // 4-digit code; null for STATE_CHANGED
    Instant eta                   // latest ETA; null if not applicable
)
```

**`NotificationEventType` enum (in `common/port/dto/`):**

```java
public enum NotificationEventType {
    OTP_GENERATED,   // M4 generated OTP for pickup verification
    STATE_CHANGED    // shipment entered a new state
}
```

**When M4 sends notifications:**

| Trigger | `type` | `otp` | `newState` | `eta` |
|---|---|---|---|---|
| DA assigned (entering `PICKUP_ASSIGNED`) | `OTP_GENERATED` | 4-digit OTP | null | null |
| OTP resend request | `OTP_GENERATED` | fresh 4-digit OTP | null | null |
| `AT_ORIGIN_HUB` entered (ETA recalculated) | `STATE_CHANGED` | null | `AT_ORIGIN_HUB` | updated ETA |
| Every other state transition | `STATE_CHANGED` | null | the new state | null |
| Cancellation | `STATE_CHANGED` | null | `CANCELLED` | null |

**Rules the notification service must follow:**
- Select channels (SMS/WhatsApp/email) and templates based on `type` and `newState` — M4 does not dictate channel.
- Own retry logic — M4 fires and forgets on `oneday.notifications.requested`.
- Mask PII in all logs.

---

### 1.5 M5 — DA Events (Kafka)

**Topic:** `oneday.da.events`  
**Partition key:** `shipment_id`  
**Enum class:** `com.oneday.common.kafka.enums.DaEventType`

M4 consumes the following `DaEventType` values and drives state transitions from them:

| `event_type` | State transition M4 performs | Side-effects |
|---|---|---|
| `PICKUP_ASSIGNED` | `BOOKED → PICKUP_ASSIGNED` | Generates 4-digit OTP (10-min TTL); sends via `NotificationPort` (type=`OTP_GENERATED`) |
| `PICKUP_FAILED` | `PICKUP_ASSIGNED → PICKUP_FAILED` | Publishes `STATE_CHANGED` event to `oneday.shipments.events`; M11 will pick it up |
| `VAN_HANDOFF_COMPLETED` | `PICKED_UP → HANDED_TO_PICKUP_VAN` | None |
| `DROP_ASSIGNED` | `HANDED_TO_DROP_VAN → DROP_ASSIGNED` | None |
| `DROP_COLLECTED` | `DROP_ASSIGNED → DROP_COLLECTED` | None |
| `DROP_COMPLETED` | `DROP_COLLECTED → DROPPED` | None |
| `DROP_FAILED` | `DROP_COLLECTED → DELIVERY_FAILED` | Publishes `STATE_CHANGED` event; M11 will pick it up |

> **`PICKUP_COMPLETED` is NOT consumed by M4.** M5 may produce it for other consumers (M10), but M4 has no handler for it. `PICKED_UP` is triggered exclusively by the OTP verify HTTP endpoint.

**Minimum required fields M5 must include in every event:**

```json
{
  "shipment_id": "<uuid>",
  "event_type": "<DaEventType value>",
  "occurred_at": "<ISO 8601 UTC>"
}
```

---

### 1.6 M7 — Hub Events (Kafka)

**Topic:** `oneday.hub.events`  
**Partition key:** `shipment_id`  
**Enum class:** `com.oneday.common.kafka.enums.HubEventType`

| `event_type` | State transition M4 performs | Applicable path |
|---|---|---|
| `STAND_ASSIGNED` | `AT_ORIGIN_HUB → ORIGIN_HUB_PROCESSING` | All |
| `BAG_CREATED` | `ORIGIN_HUB_PROCESSING → IN_TAKEOFF_BAG` | All |
| `SAMECITY_OUTBOUND` | `IN_TAKEOFF_BAG → HANDED_TO_DROP_VAN` | SAME_CITY only |
| `DEST_SORT_COMPLETE` | `AT_DEST_HUB → DEST_HUB_PROCESSING` | INTERCITY only |
| `DROP_VAN_HANDOFF` | `DEST_HUB_PROCESSING → HANDED_TO_DROP_VAN` | DA_DELIVERY only |

> **OD-9 (open):** The event triggering `DEST_HUB_PROCESSING → AWAITING_HUB_COLLECT` (HUB_COLLECT path) is not yet decided. Recommended: a new `HUB_COLLECT_STAGED` value in `HubEventType`. Team must agree before M7 implementation. M4 will add the handler once the event type is confirmed.

**Minimum required fields per event:**

```json
{
  "shipment_id": "<uuid>",
  "event_type": "<HubEventType value>",
  "occurred_at": "<ISO 8601 UTC>"
}
```

---

### 1.7 M8 — Scan Events (Kafka)

**Topic:** `oneday.scan.events`  
**Partition key:** `shipment_id`  
**Enum class:** `com.oneday.common.kafka.enums.ScanEventType`

| `event_type` | State transition M4 performs | Side-effects |
|---|---|---|
| `HUB_ORIGIN_IN` | `HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB` | Calls `EtaPort.fetchEta(AT_ORIGIN_HUB)`; stores as `eta_updated`; sends customer notification with updated ETA |
| `SELF_DROP_ACCEPTED` | `AWAITING_SELF_DROP → AT_ORIGIN_HUB` | Same side-effects as `HUB_ORIGIN_IN` above |
| `GHA_ACCEPTANCE` | `DISPATCHED_TO_AIRPORT → AT_AIRPORT` | None |
| `HUB_DEST_IN` | `DISPATCHED_TO_HUB → AT_DEST_HUB` | None |
| `LABEL_GENERATED` | **No state transition** | Updates `Shipment.parcel_id` with value from event; sets `label_status = READY` |
| `HUB_COLLECT_COMPLETED` | `AWAITING_HUB_COLLECT → HUB_COLLECTED` | None |

**Special requirement for `LABEL_GENERATED`:**

M4 emits `ShipmentCreatedEvent` on `oneday.shipments.events` immediately after booking. M8 consumes this event and generates the barcode label. When done, M8 must produce a `LABEL_GENERATED` event on `oneday.scan.events` with the following fields:

```json
{
  "shipment_id": "<uuid>",
  "event_type": "LABEL_GENERATED",
  "parcel_id": "<M8-assigned barcode string, e.g. '1DDBLR00042'>",
  "occurred_at": "<ISO 8601 UTC>"
}
```

M4 will update `shipments.parcel_id` with this value. **`parcel_id` must be non-null before a shipment reaches `PICKUP_ASSIGNED`** — M4 will fire an ops alert if it is null at that state. This gives M8 the window between booking and DA assignment (typically minutes) to generate the label.

**Minimum required fields per scan event:**

```json
{
  "shipment_id": "<uuid>",
  "event_type": "<ScanEventType value>",
  "occurred_at": "<ISO 8601 UTC>"
}
```

---

### 1.8 M9 — Flight Events (Kafka)

**Topic:** `oneday.flight.events`  
**Partition key:** `shipment_id`  
**Enum class:** `com.oneday.common.kafka.enums.FlightEventType`

| `event_type` | State transition M4 performs | Applicable path |
|---|---|---|
| `DEPARTED` | `AT_AIRPORT → DEPARTED` | INTERCITY only |
| `LANDED` | `DEPARTED → LANDED` | INTERCITY only |
| `RTO_IN_TRANSIT` | `RTO_INITIATED → RTO_IN_TRANSIT` | INTERCITY only |

> M9 produces flight events per shipment (partitioned on `shipment_id`), not per flight. If a single flight carries 200 shipments, M9 must emit 200 individual events — one per shipment.

**Minimum required fields:**

```json
{
  "shipment_id": "<uuid>",
  "event_type": "<FlightEventType value>",
  "occurred_at": "<ISO 8601 UTC>"
}
```

---

### 1.9 M6 — Cron Events (Kafka)

**Topic:** `oneday.cron.events`  
**Partition key:** `shipment_id`  
**Enum class:** `com.oneday.common.kafka.enums.CronEventType`

| `event_type` | State transition M4 performs | Applicable path |
|---|---|---|
| `DEPARTED_HUB` | `IN_TAKEOFF_BAG → DISPATCHED_TO_AIRPORT` | INTERCITY only |
| `DEPARTED_AIRPORT` | `LANDED → DISPATCHED_TO_HUB` | INTERCITY only |

> Same as M9: M6 must emit one event per shipment in the bag/van, not one event per van/bag.

**Minimum required fields:**

```json
{
  "shipment_id": "<uuid>",
  "event_type": "<CronEventType value>",
  "occurred_at": "<ISO 8601 UTC>"
}
```

---

### 1.10 M11 — Exception Events (Kafka)

**Topic:** `oneday.exceptions.events`  
**Partition key:** `shipment_id`  
**Enum class:** `com.oneday.common.kafka.enums.ExceptionsEventType`

| `event_type` | State transition M4 performs | Notes |
|---|---|---|
| `RTO_INITIATED` | `DELIVERY_FAILED → RTO_INITIATED` | M11 triggers this after deciding no further delivery attempt is viable |
| `PICKUP_RESCHEDULED` | `PICKUP_FAILED → PICKUP_ASSIGNED` | M11 reschedules a failed pickup attempt |
| `DELIVERY_RESCHEDULED` | `DELIVERY_FAILED → DROP_ASSIGNED` | M11 reschedules a failed delivery attempt |
| `RTO_COMPLETED` | `RTO_IN_TRANSIT → RTO_COMPLETED` | Terminal state — parcel returned to sender |

> M4 **never self-initiates RTO**. M11 owns the entire failure escalation lifecycle. M4 only records the transitions M11 instructs.

**Minimum required fields:**

```json
{
  "shipment_id": "<uuid>",
  "event_type": "<ExceptionsEventType value>",
  "occurred_at": "<ISO 8601 UTC>"
}
```

---

## 2. What M4 Exposes to Other Modules (Outbound)

---

### 2.1 Kafka Events Produced by M4

**Topic:** `oneday.shipments.events`  
**Partition key:** `shipment_id`  
**Serialization:** JSON, snake_case field names (Jackson `SNAKE_CASE` strategy configured globally)

All events share a common envelope:

```json
{
  "event_id": "<uuid>",
  "event_type": "CREATED | STATE_CHANGED | CANCELLED",
  "schema_version": "1.0",
  "occurred_at": "<ISO 8601 UTC>",
  "shipment_id": "<uuid>",
  "shipment_ref": "1DD-BLR-20260524-000042"
}
```

---

#### Event: `CREATED`

Emitted immediately after a successful booking.

**Consumers:** M5 (DA assignment), M8 (label generation), M10 (SLA start)

**Additional fields:**

| Field | Type | Description |
|---|---|---|
| `customer_type` | `B2C \| B2B \| C2C` | Drives pricing and auth |
| `payment_mode` | `PREPAID \| COD \| null` | Null for B2B (credit); M5 DA must collect cash for COD at delivery |
| `delivery_type` | `INTERCITY \| SAME_CITY` | M5 uses this to decide routing logic |
| `pickup_type` | `DA_PICKUP \| SELF_DROP` | M5 uses this to decide whether to assign a DA |
| `drop_type` | `DA_DELIVERY \| HUB_COLLECT` | M5 uses this to decide last-mile action |
| `origin_city` | `String` | City code, e.g. `"BLR"` |
| `origin_pincode` | `String` | |
| `origin_tile_id` | `UUID` | Grid tile from M3; M5 uses for DA assignment |
| `origin_lat` | `Double` | |
| `origin_lon` | `Double` | |
| `dest_city` | `String` | |
| `dest_pincode` | `String` | |
| `dest_lat` | `Double` | |
| `dest_lon` | `Double` | |
| `chargeable_weight_grams` | `Integer` | As computed by M4 and confirmed by M2 |
| `sla_commitment_minutes` | `Integer` | From M9 EtaResult |
| `eta_promised` | `Instant` | From M9 EtaResult; M10 uses as SLA deadline |
| `receiver_name` | `String` | |
| `receiver_phone` | `String` | |
| `b2b_account_id` | `UUID \| null` | Null for B2C and C2C |
| `sender_name` | `String` | M8 uses for label printing |
| `sender_address_line` | `String` | M8 uses for label printing |
| `receiver_address_line` | `String` | M8 uses for label printing |

**Java class:** `com.oneday.common.kafka.events.ShipmentCreatedEvent` (extends `BaseShipmentEvent`)

---

#### Event: `STATE_CHANGED`

Emitted on every state transition.

**Consumers:** M10 (SLA tracking), M11 (exception checks), Notification service

**Additional fields:**

| Field | Type | Description |
|---|---|---|
| `from_state` | `ShipmentState \| null` | Null only for the initial `BOOKED` entry |
| `to_state` | `ShipmentState` | The new state |
| `triggered_by` | `String` | Identifier of the actor/service that caused the transition |
| `trigger_source` | `String` | `API`, `KAFKA_EVENT`, or `SYSTEM` |
| `eta_updated` | `Instant \| null` | Populated only when EtaPort was called on this transition (at `AT_ORIGIN_HUB`) |

**Java class:** `com.oneday.common.kafka.events.ShipmentStateChangedEvent` (extends `BaseShipmentEvent`)

---

#### Event: `CANCELLED`

Emitted when a shipment is cancelled via the cancellation API.

**Consumers:** M5 (remove from DA queue), M10 (close SLA tracking)

**Additional fields:**

| Field | Type | Description |
|---|---|---|
| `cancelled_at_state` | `ShipmentState` | The state the shipment was in when cancelled |
| `reason` | `String` | Customer-provided cancellation reason |
| `refund_initiated` | `Boolean` | True if a Razorpay refund was triggered |
| `refund_amount_paise` | `Long \| null` | Null if no refund (COD or B2B) |

**Java class:** `com.oneday.common.kafka.events.ShipmentCancelledEvent` (extends `BaseShipmentEvent`)

---

### 2.2 Internal REST APIs

These endpoints are **not on the public load balancer**. Auth requires an internal service token.

---

#### `GET /internal/v1/shipments/{id}` — Full shipment record

**Used by:** M5, M7, M9, M10, M11

Returns the full `Shipment` entity including all fields. Use this when you need current state or metadata about a shipment identified by its UUID.

---

#### `GET /internal/v1/shipments/by-ref/{ref}` — Lookup by human-readable ref

**Used by:** M7, M8

Example: `GET /internal/v1/shipments/by-ref/1DD-BLR-20260524-000042`

Use when you have a scan event or manifest entry with the human-readable ref but not the UUID.

---

#### `GET /internal/v1/shipments?flight_id={uuid}&state={state}` — Shipments on a flight

**Used by:** M9

| Param | Type | Required | Description |
|---|---|---|---|
| `flight_id` | UUID | Yes | Assigned flight UUID |
| `state` | String | No | Filter by current `ShipmentState` |

M9 uses this to bulk-update states on flight departure and arrival.

---

#### `POST /internal/v1/shipments/{ref}/pickup-otp/verify` — Verify pickup OTP

**Used by:** M5 (DA app calls this after customer provides OTP to DA)

**Request body:**
```json
{ "otp": "4821" }
```

**Behaviour:**
- Success (`204 No Content`): transitions `PICKUP_ASSIGNED → PICKED_UP`; OTP is consumed and invalidated.
- Wrong OTP (`422`): no state change; DA must retry or call resend.
- Expired OTP (`422`): DA must call the resend endpoint.
- Wrong state (`409`): shipment is not in `PICKUP_ASSIGNED`.

---

#### `POST /internal/v1/shipments/{ref}/pickup-otp/resend` — Resend pickup OTP

**Used by:** M5 (when DA requests a fresh OTP)

**Behaviour:**
- Invalidates previous OTP; generates a fresh 4-digit OTP with a new 10-minute TTL.
- Sends to customer's registered phone via `NotificationPort`.
- Maximum **3 resends** per pickup attempt; returns `429 RESEND_LIMIT_EXCEEDED` beyond that.

---

#### `PATCH /internal/v1/shipments/{id}/state` — Force state transition

**Used by:** M7 only (for synchronous hub flows — prefer Kafka events in all other cases)

**Request body:**
```json
{
  "target_state": "AT_ORIGIN_HUB",
  "trigger_source": "KAFKA_EVENT",
  "event_ref": "kafka-msg-key-abc123",
  "triggered_by": "m7-hub-service",
  "notes": "Hub in-scan confirmed at stand 4B"
}
```

M4's state machine validates the transition. Returns `409` if the transition is illegal from the current state.

---

## 3. Shared Types Reference

All types below live in the `common` module (`com.oneday.common.*`). Every other module already depends on `common`.

---

### 3.1 ShipmentState Enum (27 values)

```java
// com.oneday.common.domain.enums.ShipmentState
BOOKED
PICKUP_ASSIGNED         // DA_PICKUP only
PICKED_UP               // DA_PICKUP only
HANDED_TO_PICKUP_VAN    // DA_PICKUP only
AWAITING_SELF_DROP      // SELF_DROP only
AT_ORIGIN_HUB
ORIGIN_HUB_PROCESSING
IN_TAKEOFF_BAG
DISPATCHED_TO_AIRPORT   // INTERCITY only
AT_AIRPORT              // INTERCITY only
DEPARTED                // INTERCITY only
LANDED                  // INTERCITY only
DISPATCHED_TO_HUB       // INTERCITY only
AT_DEST_HUB             // INTERCITY only
DEST_HUB_PROCESSING     // INTERCITY only
HANDED_TO_DROP_VAN
DROP_ASSIGNED           // DA_DELIVERY only
DROP_COLLECTED          // DA_DELIVERY only
DROPPED                 // DA_DELIVERY only (terminal success)
AWAITING_HUB_COLLECT    // HUB_COLLECT only
HUB_COLLECTED           // HUB_COLLECT only (terminal success)
PICKUP_FAILED
DELIVERY_FAILED
RTO_INITIATED
RTO_IN_TRANSIT          // INTERCITY only
RTO_COMPLETED           // terminal success (returned to sender)
CANCELLED               // terminal
```

**SQL ENUM** (`shipment_state` in PostgreSQL) has the same 27 values in the same order.

---

### 3.2 Other Domain Enums

```java
// com.oneday.common.domain.enums.CustomerType
B2C, B2B, C2C

// com.oneday.common.domain.enums.DeliveryType
INTERCITY, SAME_CITY

// com.oneday.common.domain.enums.PaymentMode
PREPAID, COD

// com.oneday.common.domain.enums.PickupType
DA_PICKUP, SELF_DROP

// com.oneday.common.domain.enums.DropType
DA_DELIVERY, HUB_COLLECT
```

---

### 3.3 Kafka Topics

```java
// com.oneday.common.kafka.KafkaTopics
SHIPMENTS_EVENTS        = "oneday.shipments.events"       // produced by M4
DA_EVENTS               = "oneday.da.events"              // produced by M5, consumed by M4
HUB_EVENTS              = "oneday.hub.events"             // produced by M7, consumed by M4
SCAN_EVENTS             = "oneday.scan.events"            // produced by M8, consumed by M4
FLIGHT_EVENTS           = "oneday.flight.events"          // produced by M9, consumed by M4
CRON_EVENTS             = "oneday.cron.events"            // produced by M6, consumed by M4
EXCEPTIONS_EVENTS       = "oneday.exceptions.events"      // produced by M11, consumed by M4
NOTIFICATIONS_REQUESTED = "oneday.notifications.requested"// consumed by notification service
SHIPMENTS_DLQ           = "oneday.shipments.dlq"          // M4 dead-letter queue
```

---

## 4. Event Routing: What Triggers What

Complete mapping of every incoming Kafka event to the state transition M4 performs:

| Source module | `event_type` | From state | To state |
|---|---|---|---|
| M5 | `PICKUP_ASSIGNED` | `BOOKED` | `PICKUP_ASSIGNED` + OTP sent |
| M5 | `PICKUP_FAILED` | `PICKUP_ASSIGNED` | `PICKUP_FAILED` |
| M5 | `VAN_HANDOFF_COMPLETED` | `PICKED_UP` | `HANDED_TO_PICKUP_VAN` |
| M5 | `DROP_ASSIGNED` | `HANDED_TO_DROP_VAN` | `DROP_ASSIGNED` |
| M5 | `DROP_COLLECTED` | `DROP_ASSIGNED` | `DROP_COLLECTED` |
| M5 | `DROP_COMPLETED` | `DROP_COLLECTED` | `DROPPED` |
| M5 | `DROP_FAILED` | `DROP_COLLECTED` | `DELIVERY_FAILED` |
| M7 | `STAND_ASSIGNED` | `AT_ORIGIN_HUB` | `ORIGIN_HUB_PROCESSING` |
| M7 | `BAG_CREATED` | `ORIGIN_HUB_PROCESSING` | `IN_TAKEOFF_BAG` |
| M7 | `SAMECITY_OUTBOUND` | `IN_TAKEOFF_BAG` | `HANDED_TO_DROP_VAN` *(SAME_CITY only)* |
| M7 | `DEST_SORT_COMPLETE` | `AT_DEST_HUB` | `DEST_HUB_PROCESSING` |
| M7 | `DROP_VAN_HANDOFF` | `DEST_HUB_PROCESSING` | `HANDED_TO_DROP_VAN` |
| M7 | *(TBD — OD-9)* | `DEST_HUB_PROCESSING` | `AWAITING_HUB_COLLECT` *(HUB_COLLECT only)* |
| M8 | `HUB_ORIGIN_IN` | `HANDED_TO_PICKUP_VAN` | `AT_ORIGIN_HUB` + ETA recalculated |
| M8 | `SELF_DROP_ACCEPTED` | `AWAITING_SELF_DROP` | `AT_ORIGIN_HUB` + ETA recalculated |
| M8 | `GHA_ACCEPTANCE` | `DISPATCHED_TO_AIRPORT` | `AT_AIRPORT` |
| M8 | `HUB_DEST_IN` | `DISPATCHED_TO_HUB` | `AT_DEST_HUB` |
| M8 | `LABEL_GENERATED` | *(no transition)* | Updates `parcel_id` only |
| M8 | `HUB_COLLECT_COMPLETED` | `AWAITING_HUB_COLLECT` | `HUB_COLLECTED` |
| M9 | `DEPARTED` | `AT_AIRPORT` | `DEPARTED` |
| M9 | `LANDED` | `DEPARTED` | `LANDED` |
| M9 | `RTO_IN_TRANSIT` | `RTO_INITIATED` | `RTO_IN_TRANSIT` *(INTERCITY only)* |
| M6 | `DEPARTED_HUB` | `IN_TAKEOFF_BAG` | `DISPATCHED_TO_AIRPORT` *(INTERCITY only)* |
| M6 | `DEPARTED_AIRPORT` | `LANDED` | `DISPATCHED_TO_HUB` |
| M11 | `RTO_INITIATED` | `DELIVERY_FAILED` | `RTO_INITIATED` |
| M11 | `PICKUP_RESCHEDULED` | `PICKUP_FAILED` | `PICKUP_ASSIGNED` |
| M11 | `DELIVERY_RESCHEDULED` | `DELIVERY_FAILED` | `DROP_ASSIGNED` |
| M11 | `RTO_COMPLETED` | `RTO_IN_TRANSIT` | `RTO_COMPLETED` |

**Any transition not in this table will be rejected with a `409` and the event will be parked on `oneday.shipments.dlq`.**

---

## 5. Critical Rules All Teams Must Follow

**1. Always use `shipment_id` as the Kafka partition key.**  
M4 processes events per-shipment serially on a single partition. If you use a different key (e.g. DA ID, flight ID), M4 may receive events for the same shipment out of order, causing DLQ parking and requiring manual ops replay.

**2. Always include `shipment_id`, `event_type`, and `occurred_at` in every event.**  
These are the minimum fields M4 requires to route and process the event. Missing any of these will cause M4 to park the event on DLQ.

**3. Use `occurred_at` for the timestamp of the physical event, not `Instant.now()` at publish time.**  
M4 and M9 use `occurred_at` for ETA calculations and audit history. Publish lag must not contaminate event timestamps.

**4. Produce one event per shipment, not one event per bag/flight/van.**  
M5, M6, M7, and M9 all handle batches (bags, flights, van loads). For any batch operation that affects multiple shipments, you must emit one Kafka event per shipment. M4 has no concept of batches.

**5. Do not call M4's internal HTTP endpoints for state transitions that have a corresponding Kafka event.**  
`PATCH /internal/v1/shipments/{id}/state` exists for M7 synchronous hub flows only. Every other module must drive state changes via Kafka. HTTP state-patch bypasses the consumer group, breaks replay capabilities, and creates split audit trails.

**6. `parcel_id` must be populated before `PICKUP_ASSIGNED`.**  
M8 must emit `LABEL_GENERATED` (with `parcel_id`) before a DA is assigned. M4 fires an ops alert if `parcel_id` is null when `PICKUP_ASSIGNED` arrives.

**7. Respect the `serviceable=false` contract in `ServiceabilityResult`.**  
M3 must never throw an exception for an unserviceable pincode — return a result object with `serviceable=false`. M4 maps this to a user-facing `422`.

**8. Jackson `SNAKE_CASE` is configured globally.**  
All Kafka events produced by M4 use snake_case JSON field names (e.g. `shipment_id`, not `shipmentId`). Consumers must deserialize accordingly.

---

## 6. Open Decisions Blocking Integration

| ID | Decision | Blocks | Status |
|---|---|---|---|
| OD-8 | Delivery verification mechanism — OTP (recommended, mirrors pickup) or QR scan | M5 DA app, M4 `DROP_COMPLETED` handler | Open |
| OD-9 | Event that triggers `DEST_HUB_PROCESSING → AWAITING_HUB_COLLECT` | M7, M4 hub consumer | Open — recommended: new `HUB_COLLECT_STAGED` in `HubEventType` |
| OD-2 | ETA circuit breaking — fail booking or proceed with null ETA if M9 is down | M9 integration | Open — M9 team to decide |

Teams blocked by these decisions should not begin implementation of the affected flows until the decisions are resolved and this document is updated.
