# M4 Orders — Controller → Service → Repository Flow

Call traces and ready-to-run requests for every endpoint M4 exposes. Pairs with
**`API-DOCS.md`** (the field-level reference).

## Setup

| Variable | Value |
|---|---|
| `base_url` | `http://localhost:8080` |
| identity | the authenticated principal — under the demo profile `DemoAuthFilter` injects a fixed principal, so **no auth header is needed** locally (prod sends `Authorization: Bearer <JWT>`) |
| `Idempotency-Key` | a fresh UUID per booking (reuse it to test replay) |

Run the app: `mvn spring-boot:run -pl app` (JDK 21). The 5 city H3 grids seed on startup, so M3
serviceability is live. PREPAID payment runs the mock gateway by default, or real Razorpay when
launched with `RAZORPAY_LIVE=true RAZORPAY_KEY_ID=… RAZORPAY_KEY_SECRET=…`.

curl helper used below (demo profile needs no auth header; for prod add `-H "Authorization: Bearer $JWT"`):
```bash
H=(-H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)")
```

---

## `/api/v1/b2c/shipments` — B2cShipmentController

### `POST /api/v1/b2c/shipments`
```
B2cShipmentController.createShipment()           [201 CREATED]
  → Authz.requireRole(C2C_CUSTOMER|B2C_CUSTOMER|ADMIN)   [403 wrong role, 401 no principal]
  → BookingService.book()
      → priceRequest()
          → ServiceabilityPort.check()           [M3 grid: coords → hex, delivery type]
          → (volumetric vs actual weight)
          → PricingPort.computeQuote()            [M2 stub: rate card + GST]
      → (PREPAID only) PaymentPort.verifySignature() → PaymentPort.capture()
      → tx { ShipmentRefService.generateRef()
             ShipmentRepository.save()            [state BOOKED]
             ShipmentStateHistoryRepository.save()
             PaymentTransactionRepository.save()  [PREPAID] }
      → ShipmentEventProducer → Kafka ShipmentCreatedEvent  [best-effort]
      → EtaPort.fetchEta()                        [M9 stub; soft]
```

**curl — COD (simplest, no payment):**
```bash
curl -s "${H[@]}" -X POST $base_url/api/v1/b2c/shipments -d '{
  "sender_name":"Ravi Kumar","sender_phone":"+919000000001",
  "origin_address":{"line1":"1 Connaught Place","city":"Delhi","pincode":"110001","state":"DL","latitude":28.6315,"longitude":77.2197},
  "origin_city":"DEL","origin_pincode":"110001",
  "receiver_name":"Priya Sharma","receiver_phone":"+919000000002",
  "dest_address":{"line1":"1 MG Road","city":"Bengaluru","pincode":"560001","state":"KA","latitude":12.9716,"longitude":77.5946},
  "dest_city":"BLR","dest_pincode":"560001",
  "weight_grams":1000,"length_cm":20,"width_cm":15,"height_cm":10,
  "pickup_type":"DA_PICKUP","drop_type":"DA_DELIVERY","payment_mode":"COD"}'
```
Expected `201` → `{ "shipment_ref":"1DD-DEL-…", "state":"BOOKED", "payment":{"mode":"COD","status":"COD_PENDING"} }`

> PREPAID: first call `POST /api/v1/payments/order`, then `POST /api/v1/payments/mock/pay`, then
> repeat the body above with `payment_mode:"PREPAID"` + the three `razorpay_*` fields (see below).

**Replay:** re-send the **same** `Idempotency-Key` → identical `201` body, no new row.

---

## `/api/v1/b2b/shipments` — B2bShipmentController

### `POST /api/v1/b2b/shipments`
```
B2bShipmentController.createShipment()           [201 CREATED]
  → Authz.requireRole(B2B_USER|ADMIN)             [403 wrong role]
  → B2bBookingService.book()
      → B2bAccountRepository.findById()            [404 if missing, 409 if inactive]
      → (caller owns account?)                      [403 AccountAccessException otherwise]
      → ServiceabilityPort.check()                 [M3]
      → (weight) → PricingPort.computeQuote()       [B2B rate card]
      → tx { B2bAccountRepository.findByIdForUpdate()   [SELECT FOR UPDATE]
             (credit check → 402 if over limit)
             ShipmentRepository.save()                  [payment_mode null]
             account.outstandingBalance += total
             ShipmentStateHistoryRepository.save() }
      → EtaPort.fetchEta()                          [soft]
```

**curl:**
```bash
curl -s "${H[@]}" -X POST $base_url/api/v1/b2b/shipments -d '{
  "b2b_account_id":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","purchase_order_ref":"PO-2026-0042",
  "sender_name":"Acme Warehouse","sender_phone":"+919000000010",
  "origin_address":{"line1":"Plot 5","city":"Delhi","pincode":"110001","state":"DL","latitude":28.63,"longitude":77.22},
  "origin_city":"DEL","origin_pincode":"110001",
  "receiver_name":"Client Receiving","receiver_phone":"+919000000011",
  "dest_address":{"line1":"MG Road","city":"Bengaluru","pincode":"560001","state":"KA","latitude":12.97,"longitude":77.59},
  "dest_city":"BLR","dest_pincode":"560001",
  "weight_grams":2000,"length_cm":30,"width_cm":25,"height_cm":20,"declared_value_paise":300000,
  "pickup_type":"DA_PICKUP","drop_type":"DA_DELIVERY"}'
```
Expected `201` → `{ "shipment_ref":"1DD-DEL-…", "state":"BOOKED" }` (no `payment` block).
Over-limit account (`b2b0dead-fade-0000-0000-000000000001`) → `402 Credit limit exceeded`.

---

## `/api/v1/payments` — PaymentController

### `POST /api/v1/payments/order`
```
PaymentController.createOrder()                   [200 OK]
  → BookingService.quote()                         [serviceability + pricing, no DB/payment]
  → PaymentPort.createOrder()                      [Razorpay orders.create (live) | local id (mock)]
```
```bash
curl -s "${H[@]}" -X POST $base_url/api/v1/payments/order -d '{ <same body as a PREPAID BookingRequest, razorpay_* omitted> }'
# → { "order_id":"order_…", "amount_paise":5900, "currency":"INR", "key_id":"rzp_test_…", "mock":false }
```

### `POST /api/v1/payments/mock/pay`  *(non-prod)*
```
MockPaymentController.pay()                        [200 OK]
  → RazorpaySignatures.sign(orderId,paymentId,secret)   [HMAC-SHA256]
```
```bash
curl -s "${H[@]}" -X POST $base_url/api/v1/payments/mock/pay -d '{"order_id":"order_…"}'
# → { "razorpay_order_id":"order_…", "razorpay_payment_id":"pay_…", "razorpay_signature":"<hmac>" }
```
Feed those three values into the PREPAID booking body. The booking's
`PaymentPort.verifySignature()` recomputes the HMAC and **rejects a tampered signature with 402**.

---

## `/internal/v1/shipments` — PickupOtpController

Both require the shipment in `PICKUP_ASSIGNED` (set by an M5 event; for a Tier-0 demo inject the
shipment into that state directly).

### `POST /internal/v1/shipments/{ref}/pickup-otp/verify`
```
PickupOtpController.verifyOtp()                   [204 No Content]
  → Authz.requireRole(DELIVERY_ASSOCIATE|ADMIN)    [403 wrong role, 401 no principal]
  → ShipmentRepository.findByShipmentRef()         [404 if missing]
  → (guard state == PICKUP_ASSIGNED)               [409 otherwise]
  → PickupOtpService.verify()                      [422 if wrong/expired/used; pessimistic lock]
  → ShipmentStateMachine.transition(PICKED_UP, actor=DA userId)
```
```bash
curl -s "${H[@]}" -X POST $base_url/internal/v1/shipments/1DD-BLR-20260603-00001/pickup-otp/verify -d '{"otp":"4821"}'
# → 204
```

### `POST /internal/v1/shipments/{ref}/pickup-otp/resend`
```
PickupOtpController.resendOtp()                   [200 OK]
  → ShipmentRepository.findByShipmentRef()
  → PickupOtpService.resend()                      [429 if resend limit (3) reached]
```
```bash
curl -s "${H[@]}" -X POST $base_url/internal/v1/shipments/1DD-BLR-20260603-00001/pickup-otp/resend
# → { "otp":"3902" }
```

---

## Notes

- The `Idempotency-Key` header is consumed by `IdempotencyFilter` before the controller; a replay
  short-circuits to the cached response.
- The caller's userId comes from the authenticated `@AuthenticationPrincipal` (JWT in prod; the
  demo principal under `!prod`) — there is **no `X-User-Id` header**. No principal → `401`.
- Each endpoint also **gates on role** (`Authz.requireRole`, `ADMIN` always allowed) → `403`;
  B2B additionally checks account ownership. The demo principal is an `ADMIN`, so demo flows pass.
- Post-`BOOKED` lifecycle transitions are **Kafka-driven** (M5/M6/M7/M8/M9/M11), not REST; there
  is no public state-advance endpoint. See `M4-STATE-MACHINE.md` and `M4-DEMO-DEPENDENCY-LADDER.md`.
