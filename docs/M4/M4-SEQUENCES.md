# M4 — Sequence Diagrams

> Extracted from [M4-ORDERS-DESIGN.md](M4-ORDERS-DESIGN.md) §8.

---

## B2C / C2C Booking (PREPAID)

```mermaid
sequenceDiagram
    participant Client
    participant M4
    participant M3
    participant M2
    participant Razorpay
    participant DB
    participant M9
    participant Kafka

    Client->>M4: POST /b2c/shipments (Idempotency-Key)
    Note over M4,DB: [Planned PR #8 wiring] IdempotencyFilter checks key before routing
    M4->>DB: Check idempotency key (miss)
    M4->>M3: check(originPin, destPin) [circuit breaker]
    M3-->>M4: {serviceable: true, delivery_type: INTERCITY}
    M4->>M2: computeQuote(request) [circuit breaker]
    M2-->>M4: {quoted_price, tax, total, breakdown, rate_card_version}
    M4->>Razorpay: verify signature + capture(paymentId, amount)
    Razorpay-->>M4: {status: CAPTURED}
    M4->>DB: BEGIN TX — insert PaymentTransaction, Shipment, StateHistory, RefCounter++
    DB-->>M4: commit OK
    Note over M4,M9: ⚠️ Known issue (DTD-10-A): ETA call currently executes inside the TX above
    M4->>M9: fetchEta(shipmentId, BOOKED, context)
    M9-->>M4: EtaResult
    M4->>DB: UPDATE shipments SET eta_promised=...
    Note over M4,Kafka: [Planned PR #14] Kafka emission not yet wired
    M4->>Kafka: emit shipment.created
    M4-->>Client: 201 {shipment_ref, eta_promised}
```

---

## B2C / C2C Booking (COD)

```mermaid
sequenceDiagram
    participant Client
    participant M4
    participant M3
    participant M2
    participant DB
    participant M9
    participant Kafka

    Client->>M4: POST /b2c/shipments (payment_mode=COD, Idempotency-Key)
    Note over M4,DB: [Planned PR #8 wiring] IdempotencyFilter checks key before routing
    M4->>DB: Check idempotency key (miss)
    M4->>M3: check(originPin, destPin) [circuit breaker]
    M3-->>M4: {serviceable: true, delivery_type: INTERCITY}
    M4->>M2: computeQuote(request) [circuit breaker]
    M2-->>M4: {quoted_price, tax, total, breakdown, rate_card_version}
    Note over M4: No Razorpay interaction for COD
    M4->>DB: BEGIN TX — insert Shipment(payment_mode=COD), StateHistory, RefCounter++
    DB-->>M4: commit OK
    Note over M4,M9: ⚠️ Known issue (DTD-10-A): ETA call currently executes inside the TX above
    M4->>M9: fetchEta(shipmentId, BOOKED, context)
    M9-->>M4: EtaResult
    M4->>DB: UPDATE shipments SET eta_promised=...
    Note over M4,Kafka: [Planned PR #14] Kafka emission not yet wired
    M4->>Kafka: emit shipment.created (payment_mode=COD)
    M4-->>Client: 201 {shipment_ref, eta_promised}
```

---

## B2B Booking (credit — no Razorpay)

```mermaid
sequenceDiagram
    participant Client
    participant M4
    participant DB
    participant M3
    participant M2
    participant M9

    Client->>M4: POST /api/v1/b2b/shipments (Idempotency-Key, X-User-Id)
    M4->>DB: findById(b2bAccountId) — check account exists + is_active
    DB-->>M4: B2bAccount {creditLimitPaise, outstandingBalancePaise, rateCardId}
    M4->>M3: check(originPincode, destPincode) [circuit breaker + time limiter]
    M3-->>M4: {serviceable: true, delivery_type: INTERCITY, originTileId}
    Note over M4: compute volumetricWeightGrams; chargeableWeightGrams = max(actual, volumetric)
    M4->>M2: computeQuote(CustomerType.B2B, deliveryType, cities, chargeableWeight, rateCardId) [circuit breaker + time limiter]
    M2-->>M4: {baseAmountPaise, taxPaise, totalPricePaise, breakdown, rateCardVersion}
    M4->>DB: BEGIN TX
    M4->>DB: SELECT FOR UPDATE b2b_accounts WHERE id=b2bAccountId
    DB-->>M4: B2bAccount (locked)
    Note over M4: check outstanding + total ≤ creditLimit — else 402 CreditLimitExceededException
    M4->>DB: generateRef → ShipmentRefCounter SELECT FOR UPDATE + increment
    M4->>DB: INSERT shipments (customerType=B2B, paymentMode=null, b2b_account_id, state=BOOKED)
    M4->>DB: UPDATE b2b_accounts SET outstanding_balance_paise = outstanding + totalPricePaise
    M4->>DB: INSERT shipment_state_history (from_state=null, to_state=BOOKED)
    DB-->>M4: commit OK
    Note over M4,M9: ⚠️ Known issue (DTD-10-A): ETA call currently executes inside the TX above (same as B2C)
    M4->>M9: fetchEta(shipmentId, BOOKED, context) [best-effort — failure does not roll back]
    M9-->>M4: EtaResult (or exception — logged as WARN)
    Note over M4: [Planned PR #14] Kafka shipment.created emission not yet wired for B2B
    M4-->>Client: 201 {shipment_ref, state, pricing, eta_promised} — payment field omitted (null, @JsonInclude NON_NULL)
```

> **No Razorpay interaction.** B2B is always credit. The `payment` field in `BookingResponse` is set to `null` and excluded from the JSON output via `@JsonInclude(NON_NULL)` on the field.

> **No new Flyway migration.** `b2b_accounts` and `shipments.b2b_account_id` already existed from PR #4. `shipments.payment_mode` is nullable; `null` is the correct value for B2B credit shipments.

---

## Kafka State Transition (e.g. BOOKED → PICKUP_ASSIGNED)

```mermaid
sequenceDiagram
    participant M5
    participant Kafka
    participant M4Consumer
    participant DB

    M5->>Kafka: publish oneday.da.events {event_type: PICKUP_ASSIGNED, shipment_id, occurred_at}
    Kafka-->>M4Consumer: deliver message
    M4Consumer->>DB: SELECT FOR UPDATE shipments WHERE id=...
    DB-->>M4Consumer: {state: BOOKED}
    M4Consumer->>DB: UPDATE state=PICKUP_ASSIGNED
    M4Consumer->>DB: INSERT shipment_state_history (event_ref=kafka_msg_key)
    DB-->>M4Consumer: commit
    M4Consumer->>Kafka: emit shipment.state_changed
    Note right of DB: Raw Kafka payload NOT stored in DB
    Note over M4Consumer: Side-effect: generate 4-digit OTP, send to customer via NotificationPort
```

> **Note:** `PICKUP_ASSIGNED → PICKED_UP` is NOT a Kafka-driven transition. `PICKED_UP` is triggered exclusively by the OTP verify HTTP endpoint (`POST /internal/v1/shipments/{ref}/pickup-otp/verify`), called by M5's DA app after the customer provides the OTP. M4 does not consume `DaEventType.PICKUP_COMPLETED` for state transitions.

---

## AT_ORIGIN_HUB — Accurate ETA Update

```mermaid
sequenceDiagram
    participant M8
    participant Kafka
    participant M4Consumer
    participant DB
    participant M9

    M8->>Kafka: publish oneday.scan.hub_origin_in {shipment_id, event_key}
    Kafka-->>M4Consumer: deliver message
    M4Consumer->>DB: SELECT FOR UPDATE shipments WHERE id=...
    M4Consumer->>DB: UPDATE state=AT_ORIGIN_HUB + INSERT state_history
    DB-->>M4Consumer: commit
    M4Consumer->>M9: fetchEta(shipmentId, AT_ORIGIN_HUB, context)
    M9-->>M4Consumer: EtaResult (accurate, flight-based)
    M4Consumer->>DB: UPDATE shipments SET eta_updated=...
    M4Consumer->>Kafka: emit shipment.state_changed (with eta_updated)
    Note over M4Consumer: Customer notification triggered by state_changed event
```
