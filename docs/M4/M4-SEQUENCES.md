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
    M4->>DB: Check idempotency key (miss)
    M4->>M3: check(originPin, destPin) [circuit breaker]
    M3-->>M4: {serviceable: true, delivery_type: INTERCITY}
    M4->>M2: computeQuote(request) [circuit breaker]
    M2-->>M4: {quoted_price, tax, total, breakdown, rate_card_version}
    M4->>Razorpay: verify signature + capture(paymentId, amount)
    Razorpay-->>M4: {status: CAPTURED}
    M4->>DB: BEGIN TX — insert PaymentTransaction, Shipment, StateHistory, RefCounter++
    DB-->>M4: commit OK
    M4->>M9: fetchEta(shipmentId, BOOKED, context)
    M9-->>M4: EtaResult
    M4->>DB: UPDATE shipments SET eta_promised=...
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
    M4->>DB: Check idempotency key (miss)
    M4->>M3: check(originPin, destPin) [circuit breaker]
    M3-->>M4: {serviceable: true, delivery_type: INTERCITY}
    M4->>M2: computeQuote(request) [circuit breaker]
    M2-->>M4: {quoted_price, tax, total, breakdown, rate_card_version}
    Note over M4: No Razorpay interaction for COD
    M4->>DB: BEGIN TX — insert Shipment(payment_mode=COD), StateHistory, RefCounter++
    DB-->>M4: commit OK
    M4->>M9: fetchEta(shipmentId, BOOKED, context)
    M9-->>M4: EtaResult
    M4->>DB: UPDATE shipments SET eta_promised=...
    M4->>Kafka: emit shipment.created (payment_mode=COD)
    M4-->>Client: 201 {shipment_ref, eta_promised}
```

---

## Kafka State Transition (e.g. PICKUP_ASSIGNED → PICKED_UP)

```mermaid
sequenceDiagram
    participant M5
    participant Kafka
    participant M4Consumer
    participant DB

    M5->>Kafka: publish oneday.da.pickup_completed {shipment_id, event_key}
    Kafka-->>M4Consumer: deliver message
    M4Consumer->>DB: SELECT FOR UPDATE shipments WHERE id=...
    DB-->>M4Consumer: {state: PICKUP_ASSIGNED}
    M4Consumer->>DB: UPDATE state=PICKED_UP
    M4Consumer->>DB: INSERT shipment_state_history (event_ref=kafka_msg_key)
    DB-->>M4Consumer: commit
    M4Consumer->>Kafka: emit shipment.state_changed
    Note right of DB: Raw Kafka payload NOT stored in DB
```

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
