# M4 — Feature Dependency Ladder (Demo Prep)

**Purpose:** rank every M4 (orders) feature by how much it leans on *other modules*, from
**least dependent (pure M4, demo-ready today)** to **most dependent (needs several modules
live)**. Use this to decide what we can demo for real, what we stub, and what we must simulate.

**Status as of 2026-06-07 (refreshed).** What changed since the first draft of this doc:

- **M3 grid is built and wired for real.** `ServiceabilityPort` is now satisfied by the real
  `grid/.../GridServiceabilityAdapter` (coordinate → H3 hex, real city/tile resolution).
  `StubServiceabilityAdapter` was **deleted**. Serviceability in the demo is genuine M3 output.
- **Payment is real.** `PaymentPort` is satisfied by `RazorpayPaymentAdapter` (mock mode by
  default; **live/test-API mode with real `rzp_test_` keys** via `RAZORPAY_*` env vars).
  `StubPaymentAdapter` was **deleted**. Signature verification is real HMAC-SHA256 in both modes.
- **Real M1 auth/authz.** The forgeable `X-User-Id` header is gone; identity + role come from the
  JWT principal (demo principal under `!prod`). Booking is gated to customer roles; **ADMIN cannot
  book** — it has read-only access to the orders database. **STATION_MANAGER** gets a city-scoped,
  custody-aware view of that database.
- **Cancellation is built** (was "not started"). `DELETE /api/v1/{b2c|b2b}/shipments/{ref}` with
  refund (PREPAID) / credit-reversal (B2B), and the rich `ShipmentCancelledEvent` on the bus.

**Still stubbed / still missing:**

- **M2 pricing** — `PricingPort` is still `StubPricingAdapter` (flat `v1.0-stub` quote, GST 18%).
- **M9 ETA** — `EtaPort` is still `StubEtaAdapter` (fixed estimate; soft, null-safe).
- **Async lifecycle producers** — M5/M6/M7/M8/M9/M11 are **not built**. The six inbound event
  streams M4 consumes have **no producers** (grid only emits its own overload/no-DA *alerts*, not
  lifecycle events), so any post-`BOOKED` state change must be **simulated by hand-publishing
  events** to the relevant topic.

---

## 1. Dependency types

| Type | Meaning | If the other module is absent |
|------|---------|-------------------------------|
| **SYNC-HARD** | Called inline during booking; circuit-broken | Booking **fails** (or uses stub) |
| **SYNC-SOFT** | Called inline, best-effort | Booking succeeds; field left null |
| **ASYNC** | M4 *consumes* the module's Kafka events to advance the state machine | That state **is never reached** (no event = no transition) |

### The external surface (where each dependency lives)

| Dependency | Port / Consumer | Owner module | Type | Status in the demo |
|------------|-----------------|--------------|------|--------------------|
| Serviceability / tile resolution | `ServiceabilityPort` | **M3** grid | SYNC-HARD | ✅ **REAL** (`GridServiceabilityAdapter`) |
| Pricing quote | `PricingPort` | **M2** pricing | SYNC-HARD | ⚠️ **stub** (`StubPricingAdapter`) |
| Payment capture (B2C prepaid) | `PaymentPort` | external (Razorpay) | SYNC-HARD¹ | ✅ **REAL** (`RazorpayPaymentAdapter`, mock or test-API) |
| ETA promise | `EtaPort` | **M9** airline | SYNC-SOFT | ⚠️ **stub** (`StubEtaAdapter`) |
| Scan events (`AT_ORIGIN_HUB`, parcelId) | `ScanEventsConsumer` | **M8** barcode | ASYNC | ❌ hand-publish to `oneday.scan.events` |
| Hub sortation / bag / manifest | `HubEventsConsumer` | **M7** hub | ASYNC | ❌ hand-publish to `oneday.hub.events` |
| DA pickup / delivery | `DaEventsConsumer` | **M5** dispatch | ASYNC | ❌ hand-publish to `oneday.da.events` |
| Cron departure | `CronEventsConsumer` | **M6** routing | ASYNC | ❌ hand-publish to `oneday.cron.events` |
| Flight legs | `FlightEventsConsumer` | **M9** airline | ASYNC | ❌ hand-publish to `oneday.flight.events` |
| Exceptions / RTO | `ExceptionsEventsConsumer` | **M11** exceptions | ASYNC | ❌ hand-publish to `oneday.exceptions.events` |

¹ Payment is only hard for B2C **PREPAID**. B2C **COD** and all **B2B** bookings skip it.

---

## 2. The ladder (least → most dependent)

```
LEAST DEPENDENT ───────────────────────────────────────────────► MOST DEPENDENT

  Tier 0            Tier 1            Tier 2                 Tier 3
  pure M4           +1 soft dep       sync booking           async lifecycle
  (no other module) (M9 ETA, opt.)    (M2 stub, M3 real,     (M5/M6/M7/M8/M9/M11)
                                       payment real)
```

| # | Feature | External deps | Demo-ready today? |
|---|---------|---------------|-------------------|
| **Tier 0 — pure M4 (zero external modules)** |
| 0.1 | 23-state shipment **state machine** engine + transition registry | none | ✅ yes (logic only) |
| 0.2 | **Idempotency** replay (`Idempotency-Key`) | none | ✅ yes (API; **no UI control** — see §5) |
| 0.3 | **Shipment reference** generation (`1DD-BLR-…`) | none | ✅ yes |
| 0.4 | **Customer-visible state** label mapping | none | ✅ yes |
| 0.5 | **Pickup OTP** generate + verify → drives `PICKED_UP` | none (BCrypt internal) | ✅ API; ⚠️ **UI tab removed** — see §5 |
| 0.6 | **B2B credit account** check (limit / outstanding) | none (internal repo + seed) | ✅ yes |
| 0.7 | **Cancellation** + refund/credit-reversal + `CANCELLED` event | Payment (PREPAID refund only) | ✅ yes (API; **UI cancel buttons added**) |
| 0.8 | **Admin orders database** (read-only) + **station-manager custody** scope | none (M1 role only) | ✅ yes (UI) |
| **Tier 1 — one SOFT dependency** |
| 1.1 | **ETA promise** on booking response | M9 (SYNC-SOFT) | ✅ via stub; null-safe without it |
| **Tier 2 — synchronous booking (blocks the request)** |
| 2.1 | **B2B booking** `POST /api/v1/b2b/shipments` | **M3 real** + M2 stub (+ M9 soft) | ✅ flow real; price stubbed |
| 2.2 | **B2C booking** `POST /api/v1/b2c/shipments` | **M3 real** + M2 stub + **payment real** (+ M9 soft) | ✅ flow real; price stubbed |
| **Tier 3 — async post-booking lifecycle (needs producers)** |
| 3.1 | `BOOKED → AT_ORIGIN_HUB` (+ parcelId) | **M8** scan | ❌ simulate event |
| 3.2 | DA pickup chain (`PICKUP_ASSIGNED`, `PICKUP_FAILED`) | **M5** | ❌ simulate event |
| 3.3 | Hub sortation / bag / takeoff-bag / manifest | **M7** | ❌ simulate event |
| 3.4 | Cron departure to airport | **M6** | ❌ simulate event |
| 3.5 | Flight legs (`DEPARTED`, `LANDED`) | **M9** | ❌ simulate event |
| 3.6 | Destination hub → drop van → `DELIVERED` | **M7** + **M5** | ❌ simulate event chain |
| 3.7 | Exceptions / **RTO** workflow | **M11** | ❌ simulate event |

---

## 3. What each tier means for the demo

### Tier 0 — demo with **nothing stubbed, nothing simulated**
These run on M4 alone. Strongest "this actually works" story:
- Book → get a real `1DD-…` reference, persisted to Postgres.
- Re-send the same `Idempotency-Key` → identical response, no duplicate row.
- Generate a pickup OTP, verify it → `PICKED_UP` transition recorded in `shipment_state_history`.
- B2B over-limit account (`QuickShip Ltd`) → `402 Credit limit exceeded`, all internal.
- **Cancel** a booking → `CANCELLED`, PREPAID refund initiated (real Razorpay test refund) or B2B
  credit reversed; `ShipmentCancelledEvent` emitted.
- **Admin / station-manager orders database** → admin sees all cities; a station manager sees only
  shipments whose pickup OR delivery is in their city, with a custody badge marking the parcels
  currently in their custody (origin holds until the destination hub's receipt scan).

### Tier 1 — soft ETA
The booking response carries an `eta_promised`. With `StubEtaAdapter` it returns a fixed
estimate; if the stub were removed the booking still succeeds with `eta_promised: null`. Safe to
show; just note the value is stubbed, not a real flight-based ETA (that's M9).

### Tier 2 — the booking path (the headline demo)
Both booking endpoints are **fully functional today**:
- `GridServiceabilityAdapter` → **real M3**: any in-boundary coordinate resolves to a real H3 hex;
  origin + destination tiles + delivery type are genuine.
- `RazorpayPaymentAdapter` → **real**: PREPAID mints a gateway order, opens checkout, verifies the
  HMAC signature, captures. Runs against the **Razorpay test API** when `RAZORPAY_LIVE=true` with
  `rzp_test_` keys; otherwise a self-contained local mock with the same real signature scheme.
- `StubPricingAdapter` → the **one remaining stub** in the booking path: a flat `v1.0-stub` quote.

**Demo caveat:** serviceability/tiles and payment are real; **only the price is a stub constant**
(and ETA, soft). Frame it as "booking flow, validation, idempotency, persistence, serviceability,
payment and events are real; the **quote** comes from a stand-in until M2 lands."

### Tier 3 — the lifecycle (needs simulation)
The 23-state machine is real, but **M4 only consumes** the events that drive it past `BOOKED`.
With no producer modules, the parcel sits at `BOOKED` forever unless we publish events ourselves.
This is why, in the orders database, **every shipment shows `BOOKED`** and inbound parcels never
flip custody to the destination city — nothing advances state. Two demo options:
- **(a) Event injection harness** — a small `!prod` script/endpoint that publishes the right
  `scan/hub/da/cron/flight/exception` events in sequence so the parcel visibly walks the full
  lifecycle. Highest-fidelity; shows the consumers + state machine working end-to-end.
- **(b) Diagram-only** — show the 23-state diagram and narrate, without live transitions.

---

## 4. Recommended demo sequence

1. **Book a B2C parcel** (Tier 2) → real ref, **real serviceability/tiles**, stubbed price,
   stubbed ETA, `BOOKED`. PREPAID runs the **real Razorpay test checkout**.
2. **Replay idempotency** (Tier 0) → same `Idempotency-Key`, identical response. *(No UI button
   yet — demo via curl/Postman, or add the "re-send last booking" control noted in §5.)*
3. **B2B credit** (Tier 0/2) → `Acme Corp` succeeds; `QuickShip Ltd` → `402` over limit.
4. **Cancel** (Tier 0) → cancel the B2C parcel → `CANCELLED` + refund; cancel a B2B parcel →
   credit reversed.
5. **Role separation** (Tier 0) → log in as ADMIN (sees all orders, cannot book) and as a
   **station manager** (sees only their city's legs, custody badges).
6. **Pickup OTP** (Tier 0) → generate + verify → `PICKED_UP`. *(UI tab was removed — restore it or
   demo via API; see §5.)*
7. **Lifecycle walk** (Tier 3) → via the injection harness (§3a), or the state diagram (§3b).

---

## 5. Gaps / refactor candidates surfaced for the demo

**Backend / module gaps**
- **No async lifecycle producers.** M5/M6/M7/M8/M9/M11 don't exist, so nothing advances a shipment
  past `BOOKED`. Tier 3 cannot be demoed without an **event-injection harness**. *(biggest gap)*
- **M2 pricing still stubbed.** Price is a flat constant — the only stub left in the booking path.
- **M9 ETA still stubbed.** `eta_promised` is a fixed estimate, not flight-based.
- **Admin B2B cancellation + ownership.** Cancelling a B2B shipment enforces account ownership;
  an admin acting on behalf needs the privileged path (`cancelAsAdmin`) that skips the ownership
  check. *(addressed when the admin cancel row-action was added.)*
- **Cold-producer event loss.** `spring.kafka.producer.properties.max.block.ms=1000` is too short
  for the first send to a cloud cluster; the first booking's `CREATED` event can time out and be
  dropped (best-effort, swallowed). Bump it (≈5000) for the dev profile. *(Local demos without a
  Kafka broker simply no-op all sends — fine for a booking demo, but events aren't observable.)*

**UI gaps**
- **Pickup OTP UI regressed.** The Pickup OTP tab was removed; the `otpVerify()`/`otpResend()` JS
  functions remain but point at deleted DOM ids (dead code). Restore a small OTP card or remove the
  dead JS.
- **No idempotency-replay control.** The UI mints a fresh `Idempotency-Key` per booking, so the
  replay behaviour can't be shown on screen. Add a "re-send last booking" button that reuses the
  prior key.
- **No customer "my shipments" list endpoint.** The customer Recent-Bookings card is **session
  only** (client-side). Cancel buttons act on those session rows; a persistent "my shipments" view
  needs a customer-scoped `GET` endpoint (does not exist yet).
- **Stub values are constants.** Price and ETA are fixed in the stubs; call it out so nobody reads
  them as real M2/M9 output.
- **ParcelId / ETA-recalc TODOs.** `ScanEventsConsumer` has open TODOs (set `parcel_id` from the
  scan event, recalc ETA on hub-in) — no-ops until M8's `ScanEvent` carries the field.

> Source of truth for the port contracts and event payloads: `M4-INTEGRATION-CONTRACTS.md`.
> State list and transitions: `M4-STATE-MACHINE.md`.
