# M4 — Feature Dependency Ladder (Demo Prep)

**Purpose:** rank every M4 (orders) feature by how much it leans on *other modules*, from
**least dependent (pure M4, demo-ready today)** to **most dependent (needs several modules
live)**. Use this to decide what we can demo for real, what we stub, and what we must simulate.

**Status as of this doc:** M2, M3, M5, M6, M7, M8, M9, M11 are **not built**. M4 talks to them
through four sync **ports** (`com.oneday.common.port.*`) and six async **Kafka consumers**.
For the demo, the four ports are satisfied by `app/.../stubs/Stub*Adapter` (active under
`@Profile("!prod")`); the six inbound event streams have **no producers**, so any post-booking
state change must be **simulated by hand-publishing events** to the relevant topic.

---

## 1. Dependency types

| Type | Meaning | If the other module is absent |
|------|---------|-------------------------------|
| **SYNC-HARD** | Called inline during booking; circuit-broken | Booking **fails** (or uses stub) |
| **SYNC-SOFT** | Called inline, best-effort | Booking succeeds; field left null |
| **ASYNC** | M4 *consumes* the module's Kafka events to advance the state machine | That state **is never reached** (no event = no transition) |

### The external surface (where each dependency lives)

| Dependency | Port / Consumer | Owner module | Type | Demo substitute |
|------------|-----------------|--------------|------|-----------------|
| Serviceability / tile resolution | `ServiceabilityPort` | **M3** grid | SYNC-HARD | `StubServiceabilityAdapter` |
| Pricing quote | `PricingPort` | **M2** pricing | SYNC-HARD | `StubPricingAdapter` |
| Payment capture (B2C prepaid) | `PaymentPort` | external (Razorpay) | SYNC-HARD¹ | `StubPaymentAdapter` |
| ETA promise | `EtaPort` | **M9** airline | SYNC-SOFT | `StubEtaAdapter` |
| Scan events (`AT_ORIGIN_HUB`, parcelId) | `ScanEventsConsumer` | **M8** barcode | ASYNC | hand-publish to `oneday.scan.events` |
| Hub sortation / bag / manifest | `HubEventsConsumer` | **M7** hub | ASYNC | hand-publish to `oneday.hub.events` |
| DA pickup / delivery | `DaEventsConsumer` | **M5** dispatch | ASYNC | hand-publish to `oneday.da.events` |
| Cron departure | `CronEventsConsumer` | **M6** routing | ASYNC | hand-publish to `oneday.cron.events` |
| Flight legs | `FlightEventsConsumer` | **M9** airline | ASYNC | hand-publish to `oneday.flight.events` |
| Exceptions / RTO | `ExceptionsEventsConsumer` | **M11** exceptions | ASYNC | hand-publish to `oneday.exceptions.events` |

¹ Payment is only hard for B2C **PREPAID**. B2C **COD** and all **B2B** bookings skip it.

---

## 2. The ladder (least → most dependent)

```
LEAST DEPENDENT ───────────────────────────────────────────────► MOST DEPENDENT

  Tier 0            Tier 1            Tier 2                 Tier 3
  pure M4           +1 soft dep       sync booking           async lifecycle
  (no other module) (M9 ETA, opt.)    (M2+M3 [+pay])         (M5/M6/M7/M8/M9/M11)
```

| # | Feature | External deps | Demo-ready today? |
|---|---------|---------------|-------------------|
| **Tier 0 — pure M4 (zero external modules)** |
| 0.1 | 23-state shipment **state machine** engine + transition registry | none | ✅ yes (logic only) |
| 0.2 | **Idempotency** replay (`Idempotency-Key`) | none | ✅ yes |
| 0.3 | **Shipment reference** generation (`1DD-BLR-…`) | none | ✅ yes |
| 0.4 | **Customer-visible state** label mapping | none | ✅ yes |
| 0.5 | **Pickup OTP** generate + verify → drives `PICKED_UP` | none (BCrypt internal) | ✅ yes² |
| 0.6 | **B2B credit account** check (limit / outstanding) | none (internal repo + seed) | ✅ yes |
| **Tier 1 — one SOFT dependency** |
| 1.1 | **ETA promise** on booking response | M9 (SYNC-SOFT) | ✅ via stub; null-safe without it |
| **Tier 2 — synchronous booking (blocks the request)** |
| 2.1 | **B2B booking** `POST /api/v1/b2b/shipments` | M3 + M2 (+ M9 soft) | ⚠️ via stubs |
| 2.2 | **B2C booking** `POST /api/v1/b2c/shipments` | M3 + M2 + Payment (+ M9 soft) | ⚠️ via stubs |
| **Tier 3 — async post-booking lifecycle (needs producers)** |
| 3.1 | `BOOKED → AT_ORIGIN_HUB` (+ parcelId) | **M8** scan | ❌ simulate event |
| 3.2 | DA pickup chain (`PICKUP_ASSIGNED`, `PICKUP_FAILED`) | **M5** | ❌ simulate event |
| 3.3 | Hub sortation / bag / takeoff-bag / manifest | **M7** | ❌ simulate event |
| 3.4 | Cron departure to airport | **M6** | ❌ simulate event |
| 3.5 | Flight legs (`IN_FLIGHT`, `LANDED`) | **M9** | ❌ simulate event |
| 3.6 | Destination hub → drop van → `DELIVERED` | **M7** + **M5** | ❌ simulate event chain |
| 3.7 | Exceptions / **RTO** workflow | **M11** | ❌ simulate event |

² OTP *verify* is pure M4, but reaching the pre-state `PICKUP_ASSIGNED` first needs an M5 event
(Tier 3.2). For a Tier-0-only demo, inject the shipment directly into `PICKUP_ASSIGNED`.

---

## 3. What each tier means for the demo

### Tier 0 — demo with **nothing stubbed, nothing simulated**
These run on M4 alone. Strongest "this actually works" story:
- Book → get a real `1DD-…` reference, persisted to Postgres.
- Re-send the same `Idempotency-Key` → identical response, no duplicate row.
- Generate a pickup OTP, verify it → `PICKED_UP` transition recorded in `shipment_state_history`.
- B2B over-limit account (`QuickShip Ltd`) → `402 Credit limit exceeded`, all internal.

### Tier 1 — soft ETA
The booking response carries an `eta_promised`. With `StubEtaAdapter` it returns a fixed
estimate; if the stub were removed the booking still succeeds with `eta_promised: null`. Safe to
show; just note the value is stubbed, not a real flight-based ETA (that's M9).

### Tier 2 — the booking path (the headline demo)
Both booking endpoints are **fully functional today** because the two hard ports (M3
serviceability, M2 pricing) and payment are stubbed:
- `StubServiceabilityAdapter` → returns serviceable + a fixed `originTileId`.
- `StubPricingAdapter` → returns a flat quote (`v1.0-stub` rate card, GST 18%).
- `StubPaymentAdapter` → auto-captures PREPAID.

**Demo caveat:** the numbers (price, tile, ETA) are stub constants, not real M2/M3 output. Frame
it as "the booking *flow, validation, idempotency, persistence and events* are real; the quote
and serviceability values come from stand-ins until M2/M3 land."

### Tier 3 — the lifecycle (needs simulation)
The 23-state machine is real, but **M4 only consumes** the events that drive it past `BOOKED`.
With no producer modules, the parcel sits at `BOOKED`/`AT_ORIGIN_HUB` forever unless we publish
events ourselves. Two demo options:
- **(a) Event injection harness** — a small script/endpoint that publishes the right
  `scan/hub/da/cron/flight/exception` events in sequence so the parcel visibly walks the full
  lifecycle. Highest-fidelity, shows the consumers + state machine working end-to-end.
- **(b) Diagram-only** — show the 23-state diagram and narrate, without live transitions.

---

## 4. Recommended demo sequence

1. **Book a B2C parcel** (Tier 2) → real ref, price, ETA, `BOOKED`. *(First booking after a
   restart loses its Kafka `CREATED` event — see §5; book once to warm the producer, then again
   for the live audience.)*
2. **Replay idempotency** (Tier 0) → same `Idempotency-Key`, identical response.
3. **B2B credit** (Tier 0/2) → `Acme Corp` succeeds; `QuickShip Ltd` → `402` over limit.
4. **Pickup OTP** (Tier 0) → generate + verify → `PICKED_UP`.
5. **Lifecycle walk** (Tier 3) → via the injection harness (§3a), or the state diagram (§3b).

---

## 5. Gaps / refactor candidates surfaced for the demo

- **Cold-producer event loss.** `spring.kafka.producer.properties.max.block.ms=1000`
  (`app/.../application.properties`) is too short for the first send to a cloud cluster; the
  first booking's `CREATED` event times out and is dropped (best-effort, swallowed). Bump it
  (≈5000) for the dev profile so no event is lost mid-demo.
- **No event-injection harness.** Tier 3 cannot be demoed without one. Build a `!prod`-only
  internal endpoint or script that publishes module events to advance a shipment. *(biggest
  demo gap)*
- **Cancellation not built.** `POST .../cancel` (PR #13) is not started — the `CANCELLED`
  branch of the state machine can't be triggered from the API yet.
- **Stub values are constants.** Price, tile, and ETA are fixed in the stubs; fine for a flow
  demo, but call it out so nobody reads them as real M2/M3/M9 output.
- **ParcelId / ETA-recalc TODOs.** `ScanEventsConsumer` has open TODOs (set `parcel_id` from the
  scan event, recalc ETA on hub-in) — these stay no-ops until M8's `ScanEvent` carries the field.

> Source of truth for the port contracts and event payloads: `M4-INTEGRATION-CONTRACTS.md`.
> State list and transitions: `M4-STATE-MACHINE.md`.
