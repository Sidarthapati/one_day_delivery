# M4 Orders — API Reference

The `orders` module (M4) owns the **shipment booking** surface and the **23-state shipment
lifecycle**. It exposes the customer-facing booking endpoints, the PREPAID payment
order/checkout endpoints, and the internal DA pickup-OTP endpoints. All routes are served
from the `orders` module, assembled into the `app` JAR.

> Post-booking lifecycle transitions (hub, flight, delivery, RTO …) are driven by **Kafka
> events from other modules**, not REST — so they are not in this document. See
> `M4-STATE-MACHINE.md` and `M4-INTEGRATION-CONTRACTS.md`.

## Wire format

- All request/response bodies are **JSON**, serialized **`snake_case`**
  (`spring.jackson.property-naming-strategy=SNAKE_CASE`). Field names below are the wire names.
- All monetary amounts are in **paise** (1 INR = 100 paise).
- Timestamps are **ISO-8601 instants** (UTC).

## Authentication & required headers

The caller's identity is taken from the **authenticated principal** (`@AuthenticationPrincipal`),
never from a client-supplied header — so the user a booking is attributed to cannot be forged:

- **prod** — the M1 JWT filter validates `Authorization: Bearer <token>` and sets the principal.
- **!prod (demo)** — `DemoAuthFilter` injects a synthetic principal, so the demo works without a
  token (every booking is attributed to the fixed demo user).

A request that reaches a booking endpoint with **no** principal gets **`401`**.

| Header | Required on | Meaning |
|--------|-------------|---------|
| `Authorization: Bearer <token>` | both booking endpoints (prod) | M1 JWT; the user UUID is read from the validated token. Not required under the demo profile. |
| `Idempotency-Key` | both booking endpoints | Opaque key (UUID). Replaying the same key returns the **first** response and writes **no** duplicate row (handled by `IdempotencyFilter`, which also reads the principal). |

> There is **no `X-User-Id` header** — identity is never client-supplied.

**Authorization (role gating).** Each endpoint also checks the principal's role; `ADMIN` is
always allowed. Wrong role → **`403`**.

| Endpoint | Allowed roles (+ `ADMIN`) |
|---|---|
| `POST /api/v1/b2c/shipments` | `C2C_CUSTOMER`, `B2C_CUSTOMER` |
| `POST /api/v1/b2b/shipments` | `B2B_USER` (and must **own** the `b2b_account_id`) |
| pickup-OTP verify/resend | `DELIVERY_ASSOCIATE` |

Under the demo profile the synthetic principal is an `ADMIN`, so every flow passes the gates.

---

## Error format

All errors use [RFC 9457 Problem Detail](https://www.rfc-editor.org/rfc/rfc9457):

```json
{
  "type": "about:blank",
  "title": "Route not serviceable",
  "status": 422,
  "detail": "Route not serviceable: 700001 → 110011",
  "instance": "/api/v1/b2c/shipments"
}
```

| HTTP status | Situation |
|-------------|-----------|
| `400` | Missing required header (`Idempotency-Key`) |
| `401` | No authenticated principal |
| `402` | Payment signature/capture failed; B2B credit limit exceeded |
| `403` | Authenticated but wrong role; B2B caller does not own the account |
| `404` | Shipment ref not found; B2B account not found |
| `409` | Illegal state transition; B2B account inactive; idempotency/unique conflict |
| `422` | Validation failure; route not serviceable; OTP wrong/expired/used; invalid PREPAID fields |
| `429` | OTP resend limit (3) reached |
| `503` | Downstream port timed out or its circuit breaker is open (M3/M2/payment/ETA) |

---

## The booking pipeline (what a booking does)

Both booking endpoints run the same orchestration (`BookingServiceImpl` / `B2bBookingServiceImpl`):

1. **Serviceability** — `ServiceabilityPort` → **M3 grid** resolves origin/dest WGS84 coords (or
   pincodes) to H3 hexes; returns `serviceable`, `origin_tile_id`, `dest_tile_id`, `delivery_type`
   (`SAME_CITY` / `INTERCITY`). Non-serviceable → `422`.
2. **Chargeable weight** — `max(actual, volumetric)`; volumetric = `L×W×H / 5000` kg.
3. **Pricing** — `PricingPort` → **M2** (currently a stub: flat `v1.0-stub` rate card + 18% GST).
4. **Payment** *(B2C PREPAID only)* — verify the Razorpay HMAC signature, then capture. COD and
   all B2B skip this. On verification failure → `402`.
5. **Persist** (single DB transaction) — write the `Shipment` (`BOOKED`), the initial
   `shipment_state_history` row, and the `payment_transaction` (PREPAID). If the DB write fails
   after capture, a **compensating refund** is initiated.
6. **Event** — publish `ShipmentCreatedEvent` to Kafka (best-effort).
7. **ETA** *(soft)* — `EtaPort` → **M9** (stub); failure leaves `eta_promised`/`sla_commitment_minutes` null.

> Status today: **M3 serviceability is real** (seeded grids), **payment is real Razorpay**
> (test keys), **M2 pricing and M9 ETA are stubs** (those modules aren't built).

---

## Shipment booking

### `POST /api/v1/b2c/shipments`

Book a **B2C / C2C** shipment. Supports **PREPAID** (Razorpay) and **COD**.
For PREPAID the caller must have already created a payment order (`POST /api/v1/payments/order`)
and completed checkout, supplying the resulting `razorpay_*` fields.

**Headers:** `Authorization: Bearer <token>` (prod; omitted under demo), `Idempotency-Key`
· identity comes from the authenticated principal

**Request body** — `BookingRequest`

| Field | Type | Constraints |
|-------|------|-------------|
| `sender_name` | string | required, ≤100 |
| `sender_phone` | string | required, `+91XXXXXXXXXX` |
| `sender_email` | string | optional, email, ≤254 |
| `origin_address` | [Address](#address) | required |
| `origin_city` | string | required, ≤10 — city code (e.g. `DEL`) |
| `origin_pincode` | string | required, 6 digits |
| `receiver_name` | string | required, ≤100 |
| `receiver_phone` | string | required, `+91XXXXXXXXXX` |
| `receiver_email` | string | optional, email |
| `dest_address` | [Address](#address) | required |
| `dest_city` | string | required, ≤10 |
| `dest_pincode` | string | required, 6 digits |
| `weight_grams` | int | required, >0, ≤70000 |
| `length_cm` / `width_cm` / `height_cm` | short | required, >0, ≤150 |
| `declared_value_paise` | long | optional, ≥0 |
| `pickup_type` | enum | required — `DA_PICKUP` \| `SELF_DROP` |
| `drop_type` | enum | required — `DA_DELIVERY` \| `HUB_COLLECT` |
| `payment_mode` | enum | required — `PREPAID` \| `COD` |
| `razorpay_order_id` | string | required for PREPAID |
| `razorpay_payment_id` | string | required for PREPAID |
| `razorpay_signature` | string | required for PREPAID |

```json
{
  "sender_name": "Ravi Kumar", "sender_phone": "+919000000001",
  "origin_address": { "line1": "1 Connaught Place", "city": "Delhi", "pincode": "110001", "state": "DL", "latitude": 28.6315, "longitude": 77.2197 },
  "origin_city": "DEL", "origin_pincode": "110001",
  "receiver_name": "Priya Sharma", "receiver_phone": "+919000000002",
  "dest_address": { "line1": "1 MG Road", "city": "Bengaluru", "pincode": "560001", "state": "KA", "latitude": 12.9716, "longitude": 77.5946 },
  "dest_city": "BLR", "dest_pincode": "560001",
  "weight_grams": 1000, "length_cm": 20, "width_cm": 15, "height_cm": 10,
  "declared_value_paise": 50000,
  "pickup_type": "DA_PICKUP", "drop_type": "DA_DELIVERY", "payment_mode": "PREPAID",
  "razorpay_order_id": "order_Xyz", "razorpay_payment_id": "pay_Xyz", "razorpay_signature": "<hmac>"
}
```

**Response `201`** — [BookingResponse](#bookingresponse) (includes the `payment` block).

**Roles:** `C2C_CUSTOMER` / `B2C_CUSTOMER` (+ `ADMIN`).

**Errors:** `400` missing `Idempotency-Key` · `401` unauthenticated · `403` wrong role · `402` payment verify/capture failed ·
`409` idempotency conflict · `422` validation / not serviceable / missing PREPAID fields · `503` downstream timeout/circuit open.

---

### `POST /api/v1/b2b/shipments`

Book a **B2B** shipment on **credit** (no payment step). Requires role `B2B_USER` (+`ADMIN`), and
the caller must **own** the `b2b_account_id` (`owner_user_id`) → else `403`. Debits the account's
available credit inside the booking transaction (`SELECT … FOR UPDATE`).

**Headers:** `Authorization: Bearer <token>` (prod; omitted under demo), `Idempotency-Key`
· identity from the authenticated principal

**Request body** — `B2bBookingRequest` (same parcel/address/sender/receiver fields as B2C, **plus/minus**):

| Field | Type | Constraints |
|-------|------|-------------|
| `b2b_account_id` | UUID | required |
| `purchase_order_ref` | string | optional, ≤100 |
| `declared_value_paise` | long | **required**, ≥0 |
| *(no `payment_mode`, no `razorpay_*`)* | | B2B is always credit |

```json
{
  "b2b_account_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "purchase_order_ref": "PO-2026-0042",
  "sender_name": "Acme Warehouse", "sender_phone": "+919000000010",
  "origin_address": { "line1": "Plot 5", "city": "Delhi", "pincode": "110001", "state": "DL", "latitude": 28.63, "longitude": 77.22 },
  "origin_city": "DEL", "origin_pincode": "110001",
  "receiver_name": "Client Receiving", "receiver_phone": "+919000000011",
  "dest_address": { "line1": "MG Road", "city": "Bengaluru", "pincode": "560001", "state": "KA", "latitude": 12.97, "longitude": 77.59 },
  "dest_city": "BLR", "dest_pincode": "560001",
  "weight_grams": 2000, "length_cm": 30, "width_cm": 25, "height_cm": 20,
  "declared_value_paise": 300000,
  "pickup_type": "DA_PICKUP", "drop_type": "DA_DELIVERY"
}
```

**Response `201`** — [BookingResponse](#bookingresponse) (the `payment` block is **omitted** for B2B).

**Errors:** `404` account not found · `409` account inactive · `402` credit limit exceeded ·
`403` wrong role / not account owner · `422` validation / not serviceable · `401` unauthenticated · `400` missing `Idempotency-Key`.

---

## Payments (PREPAID B2C)

### `POST /api/v1/payments/order`

Price the shipment and mint a **gateway order** before checkout. Send the same `BookingRequest`
body you intend to book; the server computes the amount via `BookingService.quote()`
(serviceability + pricing, **no** DB write, **no** payment) and creates a Razorpay order.

**Request body:** a `BookingRequest` (the `razorpay_*` fields may be omitted here).

**Response `200`** — `PaymentOrderResponse`

| Field | Type | Notes |
|-------|------|-------|
| `order_id` | string | gateway order id (`order_…`) |
| `amount_paise` | long | total to collect (incl. GST) |
| `currency` | string | `INR` |
| `key_id` | string | public Razorpay key for the checkout |
| `mock` | boolean | `true` = local test gateway; `false` = real Razorpay API |

```json
{ "order_id": "order_SwrnSV6XumGXTf", "amount_paise": 5900, "currency": "INR", "key_id": "rzp_test_…", "mock": false }
```

**Errors:** `422` not serviceable · `402` order creation failed (live mode).

---

### `POST /api/v1/payments/mock/pay`

**Non-prod only** (`@Profile("!prod")`). Stands in for Razorpay's hosted checkout: given an
`order_id`, returns a payment id + a **real HMAC-SHA256 signature** so the booking endpoint's
verification is genuinely exercised. In live mode the real Razorpay checkout produces these
instead and this route is absent.

**Request body**

| Field | Type |
|-------|------|
| `order_id` | string |

**Response `200`**

| Field | Type |
|-------|------|
| `razorpay_order_id` | string |
| `razorpay_payment_id` | string |
| `razorpay_signature` | string (hex HMAC-SHA256) |

```json
{ "razorpay_order_id": "order_…", "razorpay_payment_id": "pay_…", "razorpay_signature": "dd09f6bf…" }
```

---

## Pickup OTP (internal — DA app)

Base path `/internal/v1/shipments`. Requires role `DELIVERY_ASSOCIATE` (+`ADMIN`); the
authenticated DA is recorded as the transition actor. The shipment must be in
`PICKUP_ASSIGNED` (set by an M5 dispatch event). Wrong role → `403`, no principal → `401`.

### `POST /internal/v1/shipments/{ref}/pickup-otp/verify`

DA submits the OTP shown by the sender. On success the shipment transitions
`PICKUP_ASSIGNED → PICKED_UP` (recorded in `shipment_state_history`).

**Path params:** `ref` — shipment reference (e.g. `1DD-BLR-20260603-00001`)

**Request body**

| Field | Type | Constraints |
|-------|------|-------------|
| `otp` | string | required, exactly 4 digits |

```json
{ "otp": "4821" }
```

**Response `204`** — no body.

**Errors:** `401`/`403` auth · `404` ref not found · `409` shipment not in `PICKUP_ASSIGNED` ·
`422` OTP wrong / expired / already used.

---

### `POST /internal/v1/shipments/{ref}/pickup-otp/resend`

Invalidate the current OTP and issue a fresh one (e.g. sender didn't get the SMS). The new
cleartext OTP is returned for the DA app to display (production also SMSes it via the
notification service).

**Path params:** `ref`
**Request body:** *(empty)*

**Response `200`**
```json
{ "otp": "3902" }
```

**Errors:** `404` ref not found · `409` not in `PICKUP_ASSIGNED` · `429` resend limit (3) reached.

---

## Shared objects

### Address

Embedded in `origin_address` / `dest_address`; persisted as JSONB.

| Field | Type | Constraints |
|-------|------|-------------|
| `line1` | string | required, ≤200 |
| `line2` | string | optional, ≤200 |
| `city` | string | required, ≤100 |
| `pincode` | string | required, ≤10 |
| `state` | string | required, ≤100 |
| `landmark` | string | optional, ≤200 |
| `latitude` | number | optional, 6.0–38.0 (India) — set from the booking map pin |
| `longitude` | number | optional, 68.0–98.0 — used by M3/M5 |

### BookingResponse

| Field | Type | Notes |
|-------|------|-------|
| `shipment_ref` | string | e.g. `1DD-DEL-20260603-00001` |
| `state` | enum | machine state (`BOOKED`) |
| `state_label` | string | customer-visible label ("Order confirmed") |
| `delivery_type` | enum | `SAME_CITY` \| `INTERCITY` |
| `pricing.quoted_price_paise` | long | base freight |
| `pricing.gst_paise` | long | 18% GST |
| `pricing.total_price_paise` | long | total charged |
| `pricing.currency` | string | `INR` |
| `pricing.breakdown` | map | itemised components |
| `pricing.rate_card_version` | string | M2 rate-card snapshot |
| `eta_promised` | instant \| null | null if ETA (M9) unavailable |
| `sla_commitment_minutes` | int \| null | null if ETA unavailable |
| `tracking_url` | string | `/api/v1/shipments/{ref}/track` *(GET endpoint not yet built in M4)* |
| `parcel_id` | string \| null | always null at booking (set later by M8) |
| `label_status` | string | always `PENDING` at booking |
| `payment` | object \| null | present for B2C; **omitted** for B2B |
| `payment.mode` | enum | `PREPAID` \| `COD` |
| `payment.status` | string | `CAPTURED` (prepaid) / `COD_PENDING` (cod) |
| `payment.razorpay_payment_id` | string \| null | prepaid only |

### Enums

| Enum | Values |
|------|--------|
| `pickup_type` | `DA_PICKUP`, `SELF_DROP` |
| `drop_type` | `DA_DELIVERY`, `HUB_COLLECT` |
| `payment_mode` | `PREPAID`, `COD` |
| `delivery_type` | `SAME_CITY`, `INTERCITY` |
| `state` (booking) | `BOOKED` (full 23-state list in `M4-STATE-MACHINE.md`) |

---

## Endpoint summary

| Method | Path | Auth (role + `Idempotency-Key`) | Purpose |
|--------|------|------|---------|
| `POST` | `/api/v1/b2c/shipments` | `C2C_CUSTOMER`/`B2C_CUSTOMER` | Book B2C/C2C (PREPAID/COD) |
| `POST` | `/api/v1/b2b/shipments` | `B2B_USER` + account owner | Book B2B on credit |
| `POST` | `/api/v1/payments/order` | authenticated | Price + create gateway order (PREPAID) |
| `POST` | `/api/v1/payments/mock/pay` | non-prod only | Test-gateway signed payment |
| `POST` | `/internal/v1/shipments/{ref}/pickup-otp/verify` | `DELIVERY_ASSOCIATE` | OTP → `PICKED_UP` |
| `POST` | `/internal/v1/shipments/{ref}/pickup-otp/resend` | `DELIVERY_ASSOCIATE` | Re-issue OTP |

> Companion doc: **`API-FLOW.md`** (controller → service call traces + ready-to-run curl/Postman).
