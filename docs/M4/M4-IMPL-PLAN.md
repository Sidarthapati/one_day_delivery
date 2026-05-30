# M4 — Implementation Plan

| Field | Value |
|---|---|
| **Module** | M4 — Orders |
| **Plan version** | 1.5 |
| **Author** | Satvik |
| **Last updated** | 2026-05-30 |
| **Total PRs** | 21 |
| **Design doc** | [M4-ORDERS-DESIGN.md](M4-ORDERS-DESIGN.md) |

## Progress

| PR | Title | Status |
|----|-------|--------|
| #1 | MutableBaseEntity + Flyway multi-module strategy | ✅ Merged |
| #2 | Kafka event POJOs, topic constants in common | ✅ Merged |
| #3 | Cross-module port interfaces in common | ✅ Merged |
| #4 | Flyway migrations for all M4 tables and enums (V4_1–V4_9) | ✅ Merged |
| #5 | JPA entities for M4 domain model | ✅ Merged |
| #6 | Spring Data repositories and custom query methods | ✅ Merged |
| #7 | Shipment state machine with full transition coverage | ✅ Merged |
| #8 | Idempotency infrastructure (IdempotencyFilter, IdempotencyKeyPurgeJob, V4_10 fingerprint migration) | ✅ Merged |
| #9 | Ref gen + utility services: ShipmentRefService, DeliveryTypeResolver, PaymentPort, PickupOtpService, PickupOtpController, V4_11 pickup_otps migration, PickupOtpProperties | ✅ Merged |
| #10 | B2C PREPAID booking endpoint with Resilience4j circuit breakers (B2cShipmentController, BookingService/Impl, BookingRequest/Response DTOs, GlobalExceptionHandler, ResilienceConfig/Properties) | 🔧 Implemented — not yet committed |
| #11–#21 | COD path, B2B booking, cancellation, Kafka wiring, supporting APIs, resilience, observability | 🔲 Not started |

---

## Architectural Principles

Before the PR breakdown, these principles govern every decision in this plan:

**1. Contracts before implementation.**
M4 is a platform module. Before a single line of M4 business logic is written, the Kafka event schemas, cross-module port interfaces, and topic name constants must be published. Other teams cannot proceed without them.

**2. Cross-module contracts live in `common`, not in `orders`.**
Port interfaces that other modules implement — `PricingPort` (M2), `ServiceabilityPort` (M3), `EtaPort` (M9), `BarcodePort` (M8), `NotificationPort` — must live in `common`. If they live in `orders`, other modules would need to depend on `orders` to implement them, creating a circular dependency. Only M4-internal ports (e.g. `PaymentPort` for Razorpay) stay in `orders`.

**3. Resilience is not a phase 6 concern.**
Circuit breakers for M2 and M3 are configured alongside the booking API, not bolted on at the end. A booking endpoint without circuit breakers is not production-ready, even in development.

**4. State machine correctness is non-negotiable.**
The state machine is the source of truth for every other module. It ships with exhaustive unit tests covering every legal and illegal transition before any booking API is written on top of it.

**5. Other teams can work in parallel from Phase 1 onward.**
The phase structure is designed so that once Phase 0 contracts are merged, M2, M3, M5, M7, M8, M9 teams each have everything they need to implement against M4's contracts without waiting for M4's implementation to finish.

**6. V1 means open seams, not incomplete code.**
V1 is not a license to skip structure — it is a requirement to keep seams visible and extension points clean. Every hardcoded threshold lives in `@ConfigurationProperties`. Every decision that is not yet final is behind an interface. Kafka schemas are forward-compatible from day one. If a future engineer needs to add a state, a payment mode, or a new port — the code should say "add it here" not "refactor this first."

---

## Dependency Flow

```
Phase 0 (Contracts)
    │
    ├──► M2 team can implement PricingPort
    ├──► M3 team can implement ServiceabilityPort
    ├──► M5/M7/M8/M9 teams can start producing/consuming Kafka events
    │
Phase 1 (DB + Domain)
    │
Phase 2 (Core Engine: State Machine + Idempotency)
    │
Phase 3 (Booking APIs) ─── circuit breakers included here
    │
Phase 4 (Kafka wiring)
    │
Phase 5 (Supporting APIs)
    │
Phase 6 (Resilience hardening)
    │
Phase 7 (Observability)
```

---

## Phase 0 — Shared Contracts
> **Target: `common` module. Unblocks all other teams.**
> These PRs touch no M4 business logic. They establish the shared language of the platform.
>
> **V2 extension points:** New enums (e.g. additional `CustomerType` values), new event types, and new port interfaces are added to `common` here — not scattered across modules.

---

### `M4 IMPL - PR #1 - Add MutableBaseEntity and establish Flyway multi-module migration strategy`

**Module:** `common` + `app`

**Why first:** Every M4 entity inherits from `BaseEntity`. The `updatedAt` gap and Flyway collision risk must be resolved before any entity or migration is written in any module.

**What:**
- Add `MutableBaseEntity extends BaseEntity` with `updatedAt: Instant` managed by `@PrePersist` + `@PreUpdate`
- Mutable entities (`Shipment`, `PaymentTransaction`, `B2bAccount`) will extend `MutableBaseEntity`
- Append-only entities (`ShipmentStateHistory`, `TileDemandSnapshot`, `DaTileAssignment`) stay on `BaseEntity`
- Establish Flyway strategy: module-prefixed classpath locations (`db/migration/orders/`, `db/migration/grid/`, etc.) configured centrally in `app/application.yml`
- Move M3's existing migrations to `db/migration/grid/` and update its Flyway config accordingly

**Unlocks:** All subsequent entity and migration work across all modules.

---

### `M4 IMPL - PR #2 - Kafka event POJOs, topic constants, and common envelope in common`

**Module:** `common`

**Why second:** Kafka is the nervous system of the platform. Every module either produces or consumes M4-related events. Before any module writes a producer or consumer, the event schemas must exist as shared POJOs in `common`.

**What:**
- Topic name constants: `KafkaTopics.SHIPMENTS_EVENTS`, `KafkaTopics.DA_EVENTS`, `KafkaTopics.HUB_EVENTS`, `KafkaTopics.SCAN_EVENTS`, `KafkaTopics.FLIGHT_EVENTS`, `KafkaTopics.CRON_EVENTS`, `KafkaTopics.EXCEPTIONS_EVENTS`
- Common event envelope POJO: `BaseShipmentEvent { eventId, eventType, schemaVersion, occurredAt, shipmentId, shipmentRef }`
- Specific event POJOs extending the envelope:
  - `ShipmentCreatedEvent` (fields: `customerType`, `paymentMode`, `deliveryType`, `pickupType`, `dropType`, `originCity`, `originPincode`, `originTileId`, `originLat`, `originLon`, `destCity`, `destPincode`, `destLat`, `destLon`, `chargeableWeightGrams`, `slaCommitmentMinutes`, `etaPromised`, `receiverPhone`, `receiverName`, `b2bAccountId`, `senderName`, `senderAddressLine`, `receiverAddressLine`)
  - `ShipmentStateChangedEvent` (fields: `fromState`, `toState`, `triggeredBy`, `triggerSource`, `etaUpdated`)
  - `ShipmentCancelledEvent` (fields: `cancelledAtState`, `reason`, `refundInitiated`, `refundAmountPaise`)
- `ShipmentState` enum (all 27 values) moved to `common` so all consumers can deserialize state fields
- `EventType` enum per topic (e.g. `DaEventType { PICKUP_ASSIGNED, PICKUP_COMPLETED, ... }`)
- **`@JsonIgnoreProperties(ignoreUnknown = true)` on every event POJO and `BaseShipmentEvent` (inherited by all subclasses).** This is non-negotiable: without it, any new field added by another team to a produced event will throw `UnrecognizedPropertyException` on the M4 consumer side and park the message on the DLQ. Forward-compatible deserialization must be the default from day one.

**Unlocks:** M5, M7, M8, M9, M10, M11 can immediately start writing Kafka producers/consumers against these schemas.

---

### `M4 IMPL - PR #3 - Cross-module port interfaces in common`

**Module:** `common`

**Why third:** M2, M3, M8, M9 need to know what interface to implement. Publishing these now lets those teams work in parallel with M4's implementation. M4 will use stub implementations in tests until real module implementations are wired in `app`.

**What:**
```java
// com.oneday.common.port

PricingPort          → M2 implements; M4 calls at booking
ServiceabilityPort   → M3 implements; M4 calls at booking
EtaPort              → M9 implements; M4 calls at booking and AT_ORIGIN_HUB
BarcodePort          → M8 implements; M4 calls (async) after booking
NotificationPort     → notification service implements; M4 calls on every state change
```
- Each interface is **minimal** — only methods called in V1 booking or state transition flows. No speculative methods. If a V2 flow needs a new method, it is added then with a separate PR. Over-specifying now forces every implementor to change when the method is not yet needed.
- DTOs for each port (e.g. `QuoteRequest`, `QuoteResult`, `ServiceabilityResult`, `EtaResult`, `EtaContext`) in `common.port.dto`
- No stub implementations here — stubs live in `orders/src/test/`
- **[BD-SEAM] `BarcodePort` and `NotificationPort` are behind interfaces precisely because their implementations are V1 assumptions.** If the barcode standard (H1) or notification channel changes in V2, only the `common` implementation bean is replaced — M4's booking service is untouched.

**Unlocks:** M2 can implement `PricingPort`; M3 can implement `ServiceabilityPort`; M9 can implement `EtaPort`; M8 can implement `BarcodePort`. All independently, without waiting for M4.

---

## Phase 1 — Database & Domain Layer
> **Target: `orders` module. Pure data. No business logic.**
> Fast to review. Forms the foundation all subsequent PRs build on.
>
> **V2 extension points:** New columns for anticipated V2 features are added as `NULL` columns here — not as `NOT NULL DEFAULT ''` columns that require a data backfill migration later. New tables for V2 entities (e.g. subscription accounts, bulk manifests) are separate migrations.

---

### `M4 IMPL - PR #4 - Flyway migrations for all M4 tables and enums`

**Module:** `orders` (`db/migration/orders/`)

**What:**
- PostgreSQL ENUMs: `shipment_state` (27 values — includes `AWAITING_SELF_DROP`, `AWAITING_HUB_COLLECT`, `HUB_COLLECTED`), `customer_type`, `delivery_type`, `payment_mode`, `pickup_type` (`DA_PICKUP`, `SELF_DROP`), `drop_type` (`DA_DELIVERY`, `HUB_COLLECT`)
- `trigger_source` is **not** a PG ENUM — stored as `VARCHAR(20)` in `shipment_state_history`. It is an M4-internal audit classification (API, KAFKA_EVENT, SYSTEM), not a shared business domain type. Adding a new source requires only a Java enum value, not a DB migration.
- Tables: `shipments` (with `pickup_type` and `drop_type` columns, both NOT NULL DEFAULT), `shipment_state_history`, `payment_transactions`, `b2b_accounts`, `idempotency_keys`, `shipment_ref_counters`
- All indexes (shipment_ref, parcel_id, state, b2b_account_id, origin_tile_id, city_id+state composite, assigned_flight_id, origin_city, dest_city)
- DB trigger for `updated_at` auto-management on `shipments`, `payment_transactions`, `b2b_accounts`
- **V4_9 (added during architect review):** `UNIQUE` constraint on `shipments.idempotency_key` — prevents TOCTOU duplicate-creation race under concurrent order requests; NULLs permitted for B2B batch imports
- No Java code in this PR

**Note:** SQL-only PR. Reviewer should check:
- Index coverage and constraint correctness
- Enum completeness against the state machine
- **Nullable policy:** any column representing a V1-specific feature that may change or be extended must be `NULL`-able. Avoid `NOT NULL DEFAULT ''` for columns that are not genuinely required by every shipment type. Adding a NOT NULL constraint later on a large table is expensive; removing one is cheap.
- **No enum DDL for values with open business decisions (BD-001, BD-002).** Use `VARCHAR` for fields whose valid values are not yet finalized; convert to enum in a future migration once the decision is locked.

---

### `M4 IMPL - PR #5 - JPA entities for M4 domain model`

**Module:** `orders`

**What:**
- Remaining enums local to `orders`: `PaymentStatus`, `RefundStatus`, `TriggerSource`
- JPA entities: `Shipment` (extends `MutableBaseEntity`), `ShipmentStateHistory` (standalone — has `occurred_at` not `created_at`; extending `BaseEntity` would fail schema validation), `PaymentTransaction` (extends `MutableBaseEntity`), `B2bAccount` (extends `MutableBaseEntity`), `IdempotencyKey` (standalone with `@EmbeddedId` for composite PK `(key, user_id)` — cannot extend `BaseEntity`), `ShipmentRefCounter` (standalone with `@EmbeddedId` for composite PK `(city_code, date_key)`)
- `ShipmentState`, `PickupType`, `DropType`, and customer/delivery/payment enums imported from `common` (defined in PR #2)
- `@Column(updatable = false)` on all immutable fields; `@Column(name = "created_at", updatable = false)` inherited
- No repositories, no services

---

### `M4 IMPL - PR #6 - Spring Data repositories and custom query methods`

**Module:** `orders`

**What:**
- `ShipmentRepository`: `findByIdWithLock` (`@Lock(PESSIMISTIC_WRITE)`), `findByShipmentRef`, `findByState` (list + paginated), `findByStateAndCityId` (list + paginated), `existsByIdempotencyKey`, `findByCustomerType`, `findByAssignedFlightId`
- `ShipmentStateHistoryRepository`: `findByShipmentIdOrderByOccurredAtAsc`
- `PaymentTransactionRepository`: `findByShipmentId`, `findByRazorpayOrderId`
- `B2bAccountRepository`: `findByCityId`, `findByIsActive`, `findByBillingEmail` — **Note:** `findByIdWithLock` not yet added; needed for B2B credit check in PR #12
- `IdempotencyKeyRepository`: `deleteExpired(Instant now)` (`@Transactional @Modifying @Query`)
- `ShipmentRefCounterRepository`: `findByIdWithLock(@Param("id") ShipmentRefCounterId id)` (`@Lock(PESSIMISTIC_WRITE)`)
- `@DataJpaTest` integration tests against local PostgreSQL (`@AutoConfigureTestDatabase(replace=NONE)`) — see testing strategy note below

---

## Phase 2 — Core Engine
> **The heart of M4. Must be exhaustively tested before booking APIs are built on top.**
>
> **V2 extension points:** The state machine's `TransitionRegistry` (PR #7) is the primary extensibility seam — new states and transitions are registered here without modifying existing code. New utility services (e.g. reference formats for new city codes, new delivery types) are added to PR #9's services without touching the booking flow.

---

### `M4 IMPL - PR #7 - Shipment state machine with full transition coverage`

**Module:** `orders`

**Why its own PR:** The state machine is the most load-bearing piece of M4. Every other module depends on it being correct. It ships with the most thorough tests in the codebase.

**What:**
- `ShipmentStateMachine` (package-private implementation, public interface)
- **`TransitionRegistry` bean** — replaces a static `Map<State, Set<State>>`. Transitions are registered as named entries (`registry.register(BOOKED, PICKUP_ASSIGNED)`). This makes the registry open for extension: a future module (or a new V2 flow) can contribute additional transitions via a `TransitionRegistryConfigurer` without modifying the state machine class itself. The state machine validates against whatever is in the registry at startup.
- `pickup_type` branching at `BOOKED` (DA_PICKUP → PICKUP_ASSIGNED; SELF_DROP → AWAITING_SELF_DROP)
- `drop_type` branching at `DEST_HUB_PROCESSING` (DA_DELIVERY → HANDED_TO_DROP_VAN; HUB_COLLECT → AWAITING_HUB_COLLECT)
- `delivery_type` branching at `IN_TAKEOFF_BAG` and `RTO_INITIATED`
- `transition()` method: `SELECT FOR UPDATE` → validate → update state → insert `ShipmentStateHistory`
- `IllegalStateTransitionException` → mapped to `409 Conflict`
- `CustomerVisibleStateMapper` — maps internal states to customer-facing labels
- **Tests:** Every legal transition asserted; every illegal transition asserted to throw; SAME_CITY and INTERCITY paths; RTO branching by `delivery_type`; verify that registering an additional transition at test time takes effect without code changes

---

### `M4 IMPL - PR #8 - Idempotency infrastructure`

**Module:** `orders`

**What:**
- `IdempotencyFilter` (Spring `OncePerRequestFilter`): checks header presence → 400 if missing; looks up `(key, user_id)` in DB
- SHA-256 request body fingerprinting on first request; stored in `idempotency_keys`
- Replay path: return cached `response_status` + `response_body`; add `Idempotency-Replayed: true` header
- Body mismatch (same key, different fingerprint) → `422 IDEMPOTENCY_KEY_BODY_MISMATCH`
- `IdempotencyKeyPurgeJob` — `@Scheduled` nightly delete of expired rows
- Unit tests: miss, hit-match, hit-mismatch, expiry

---

### `M4 IMPL - PR #9 - Shipment reference generation and internal utility services` ✅ Merged

**Module:** `orders`

**What (all implemented):**
- `ShipmentRefService` interface + `ShipmentRefServiceImpl`: generates `1DD-{CITY}-{YYYYMMDD}-{NNNNN}` using `insertIfAbsent` + `SELECT FOR UPDATE` on `ShipmentRefCounter`; `Propagation.MANDATORY` — counter increment rolls back with the caller's transaction; documents the Redis upgrade path at high volume
- `DeliveryTypeResolver` (`@Component`): `resolve(origin, dest)` → `SAME_CITY` if equal ignoring case, else `INTERCITY` — pure function, zero DB calls
- `PaymentPort` interface (local to `orders/service/` — not in `common`): `verifySignature(orderId, paymentId, signature)`, `void capture(paymentId, amountPaise)`, `String initiateRefund(paymentId, amountPaise)` — Razorpay-specific; inner unchecked exceptions `PaymentVerificationException`, `PaymentCaptureException`, `PaymentRefundException`
- `PickupOtpService` interface + `PickupOtpServiceImpl`: generates 4-digit OTP using `SecureRandom`, stores BCrypt(cost=4) hash in `pickup_otps` table; exposes `generate()`, `verify()` (pessimistic-lock), `resend()` (max 3, driven by `PickupOtpProperties`); inner exceptions `OtpVerificationException`, `ResendLimitExceededException`
- `PickupOtpProperties` (`@ConfigurationProperties(prefix="orders.otp")`): `ttlMinutes=10`, `maxResendCount=3`
- `PickupOtpRepository`: `findByShipmentId`, `findByShipmentIdWithLock` (`SELECT FOR UPDATE`), `deleteByShipmentId`
- `PickupOtp` JPA entity: `pickup_otps` table; only `used` is mutable after creation
- `PickupOtpController`: `POST /internal/v1/shipments/{ref}/pickup-otp/verify` → `204 No Content` on success (transitions `PICKUP_ASSIGNED → PICKED_UP`), `409` if wrong state, `422` on wrong/expired OTP; `POST /internal/v1/shipments/{ref}/pickup-otp/resend` → `200 {"otp": "..."}` on success, `429` after 3 resends
- `OtpVerifyRequest` DTO: `@NotBlank @Pattern(regexp="\\d{4}") String otp`
- `V4_11__create_pickup_otps.sql`: `pickup_otps` table with unique index on `shipment_id` and expiry index; `ShipmentRefCounterRepository` extended with `insertIfAbsent` native query

---

## Phase 3 — Booking APIs
> **End-to-end booking flows with stub ports. Circuit breakers included here, not later.**
> PRs #11 and #12 can be developed in parallel once #10 is merged.
>
> **V2 extension points:** New booking types (e.g. B2C COD with partial prepayment, subscription accounts) are new `BookingService` branches behind the existing port interfaces — not new service classes. New `customer_type` values are added to the `CustomerType` enum in `common` and a new `BookingOrchestrator` implementation is registered.

---

### `M4 IMPL - PR #10 - B2C PREPAID booking endpoint with circuit breakers` 🔧 Implemented — not yet committed

**Module:** `orders`

**What (as implemented in working tree):**
- `POST /api/v1/b2c/shipments` — B2C PREPAID path (COD and C2C deferred to PR #11)
- `B2cShipmentController`: package-private `@RestController`; accepts `Idempotency-Key` and `X-User-Id` headers; delegates to `BookingService.book()`
  - Note: JWT auth (`@AuthenticationPrincipal`) is not yet integrated; uses `X-User-Id` header as a placeholder pending M1 integration
- `BookingService` (public interface) + `BookingServiceImpl` (package-private): orchestrates serviceability → pricing → Razorpay verify+capture → DB transaction → best-effort ETA → return 201
  - `customer_type` is hardcoded to `B2C` (no `customer_type` field in `BookingRequest`)
  - `payment_mode` is hardcoded to `PREPAID` (no `payment_mode` field in `BookingRequest`; COD is PR #11)
  - Kafka event emission (shipment.created) and notification dispatch are **not yet wired** (planned PR #14)
  - Compensating refund is initiated if DB write fails after successful Razorpay capture
- `BookingRequest` DTO: `senderName`, `senderPhone`, `senderEmail`, `originAddress`/`originCity`/`originPincode`, `receiverName`, `receiverPhone`, `receiverEmail`, `destAddress`/`destCity`/`destPincode`, `weightGrams`, `lengthCm`, `widthCm`, `heightCm`, `declaredValuePaise`, `pickupType`, `dropType`, `razorpayOrderId`, `razorpayPaymentId`, `razorpaySignature`
- `BookingResponse` DTO: `shipmentRef`, `state`, `stateLabel`, `deliveryType`, `pricing` (quotedPricePaise, gstPaise, totalPricePaise, currency, breakdown, rateCardVersion), `etaPromised`, `slaCommitmentMinutes`, `trackingUrl`, `parcelId` (null at booking), `labelStatus` ("PENDING"), `payment` (status, razorpayPaymentId)
- `GlobalExceptionHandler` (`@RestControllerAdvice`): maps `ServiceabilityException`, `DownstreamTimeoutException`, `PaymentPort.*Exception`, `MethodArgumentNotValidException` to appropriate HTTP status codes
- `ResilienceConfig`: registers `CircuitBreakerRegistry` and `TimeLimiterRegistry` beans; three named circuit breakers (`serviceability`, `pricing`, `payment`) and matching `TimeLimiter`s; `ScheduledExecutorService` bean (`resilienceScheduler`, 4 threads)
- `ResilienceProperties` (`@ConfigurationProperties(prefix="orders.resilience")`): `failureRateThreshold`, `minimumNumberOfCalls`, `slidingWindowSize`, `serviceabilityTimeoutMs`, `pricingTimeoutMs`, `paymentTimeoutMs`
- ETA call is best-effort: failure logs a WARN but does not fail the booking; `etaPromised` / `slaCommitmentMinutes` are null in the response if ETA call fails
- Idempotency key is stored on `Shipment.idempotency_key` but the `IdempotencyFilter` deduplication path is not yet wired into this endpoint (PR #8 filter exists; wiring it to the booking path is a follow-up item)
- Request validation (`@Valid` + JSR-303 constraints on `BookingRequest`)
- All Resilience4j thresholds bound to `@ConfigurationProperties` — no hardcoded literals

---

### `M4 IMPL - PR #11 - COD booking path for B2C and C2C`

**Module:** `orders`

**What:**
- Extend `BookingService` to branch on `payment_mode=COD`
- Skip `PaymentPort` entirely; no `PaymentTransaction` row created at booking
- `label_status: PENDING`, `parcel_id: null` in `201` response
- `payment_mode` included in `ShipmentCreatedEvent` so M5 knows DA must collect cash

---

### `M4 IMPL - PR #12 - B2B booking endpoint with atomic credit check`

**Module:** `orders`

**What:**
- `POST /api/v1/b2b/shipments`
- `SELECT FOR UPDATE` on `b2b_accounts` inside booking DB transaction; credit check: `outstanding_balance + total ≤ credit_limit` → `402 CREDIT_LIMIT_EXCEEDED` if violated
- `outstanding_balance_paise` incremented atomically with `Shipment` insert
- No Razorpay; B2B is credit-only
- Integration test: concurrent booking race condition (two threads, verify only one exceeds limit)

---

### `M4 IMPL - PR #13 - Cancellation API`

**Module:** `orders`

**What:**
- `DELETE /api/v1/b2c/shipments/{ref}` and `DELETE /api/v1/b2b/shipments/{ref}`
- State guard via state machine — only states permitted by BD-001 (currently up to `PICKED_UP`)
- **[BD-SEAM] Cancellation eligibility is behind a `CancellationPolicy` interface** with method `isCancellable(ShipmentState, CustomerType, PickupType): boolean`. The V1 implementation encodes per-path cutoffs: DA_PICKUP cancellable up to `PICKED_UP`; SELF_DROP cancellable up to `AWAITING_SELF_DROP`. When cutoffs are revised, only the `CancellationPolicy` bean changes.
- PREPAID → `PaymentPort.initiateRefund()`; COD → no refund; B2B → decrement `outstanding_balance_paise` atomically
- Transition to `CANCELLED` via state machine; emit `ShipmentCancelledEvent`

---

## Phase 4 — Kafka Wiring
> **Connects M4 to the rest of the platform. Producers first, consumers second.**
>
> **V2 extension points:** New event types are added to the existing `EventType` enums in `common` (PR #2) — no new topics. Consumer routing on `event_type` is a switch/dispatch table; new handlers are registered without modifying existing handlers.

---

### `M4 IMPL - PR #14 - Kafka producer wired into booking and cancellation flows`

**Module:** `orders`

**What:**
- `ShipmentEventProducer`: produces to `oneday.shipments.events` with `shipment_id` as partition key
- `CREATED` published at end of booking flow (PRs #10–12); `CANCELLED` published at end of PR #13
- `STATE_CHANGED` published by state machine on every transition (wire into PR #7 logic)
- `@EmbeddedKafka` integration tests asserting correct topic, partition key, event payload

---

### `M4 IMPL - PR #15 - Kafka consumers for DA and Hub events`

**Module:** `orders`

**What:**
- `ShipmentEventConsumer` (consumer group `m4-shipment-state-consumer`): subscribes to `oneday.da.events` and `oneday.hub.events`
- **Consumer group ID must be `@ConfigurationProperties`-bound** (`m4.kafka.consumer.group-id`). Hardcoding prevents ops from resetting offsets during incident recovery or a V2 state machine rollout without a code change + redeploy.
- Routes on `event_type` enum → `ShipmentStateMachine.transition()`; routing table is a `Map<EventType, StateTransitionHandler>` registered as Spring beans — adding a new event type means adding a new `StateTransitionHandler` bean, not touching the consumer
- `LABEL_GENERATED` (from `oneday.scan.events`) handled here: updates `parcel_id` + `label_status=READY`
- Alert (log `ERROR`) if `PICKUP_ASSIGNED` received while `parcel_id` is still null
- **Each handler must be idempotent** — processing the same event twice must produce the same DB state. This is a correctness requirement: Kafka guarantees at-least-once delivery. The state machine's `SELECT FOR UPDATE` + idempotency check covers most cases; document the assumption explicitly per handler.
- 3 retries with exponential backoff; park on `oneday.shipments.dlq` on exhaustion
- Out-of-order event handling: `409` from state machine → log `WARN` → DLQ with `rejection_reason: OUT_OF_ORDER`

---

### `M4 IMPL - PR #16 - Kafka consumers for Scan, Flight, Cron, and Exception events`

**Module:** `orders`

**What:**
- Extend `ShipmentEventConsumer` to subscribe to `oneday.scan.events`, `oneday.flight.events`, `oneday.cron.events`, `oneday.exceptions.events`
- `HUB_ORIGIN_IN` handler: `HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB` + call `EtaPort.fetchEta(AT_ORIGIN_HUB)` + update `eta_updated` + publish `STATE_CHANGED`
- `SELF_DROP_ACCEPTED` handler: `AWAITING_SELF_DROP → AT_ORIGIN_HUB` + same ETA side-effects as `HUB_ORIGIN_IN`
- `HUB_COLLECT_COMPLETED` handler: `AWAITING_HUB_COLLECT → HUB_COLLECTED` + publish `STATE_CHANGED`
- RTO transitions: `RTO_INITIATED` branches on `delivery_type` (`INTERCITY` → await `RTO_IN_TRANSIT`; `SAME_CITY` → direct to `RTO_COMPLETED`)
- `PICKUP_RESCHEDULED` / `DELIVERY_RESCHEDULED` from M11 wired into state machine

---

## Phase 5 — Supporting APIs
> PRs #17, #18, #19 can be developed in parallel.
>
> **V2 extension points:** New tracking response fields are additive-only — existing fields are never removed or renamed. Internal endpoints follow the same `/internal/v1/` versioning discipline as external APIs. B2B webhook payload fields are additive-only; breaking field changes require `/internal/v2/` webhooks with a migration window.

---

### `M4 IMPL - PR #17 - Customer tracking API with Redis caching`

**Module:** `orders`

**What:**
- `GET /api/v1/shipments/{ref}/track` — public, no JWT
- Returns: `state`, `label` (customer-visible), `eta_promised`, `eta_updated`, `label_status`, `state_history[]`
- Redis cache with **`@ConfigurationProperties`-bound TTL** (`m4.tracking.cache.ttl-seconds`; default: 60). Hardcoding 60s prevents adjusting cache freshness trade-offs in production without a deploy.
- Rate limiting: 600 req/min (Spring MVC rate limiter or bucket4j)
- `GET /api/v1/shipments` — paginated list for authenticated user (cursor-based)

---

### `M4 IMPL - PR #18 - Internal inter-module HTTP endpoints`

**Module:** `orders`

**What:**
- `GET /internal/v1/shipments/{id}` — full entity; used by M5, M7, M9, M10, M11
- `GET /internal/v1/shipments/by-ref/{ref}`
- `GET /internal/v1/shipments?flight_id={id}&state={state}` — bulk lookup for M9
- `PATCH /internal/v1/shipments/{id}/state` — synchronous force transition; documented as last resort over Kafka
- Internal service token auth (`X-Service-Token` header validated against config)
- These endpoints are the fallback when Kafka is not appropriate; document clearly in each endpoint's Javadoc

---

### `M4 IMPL - PR #19 - B2B webhook delivery with HMAC signing and retry`

**Module:** `orders`

**What:**
- On every `STATE_CHANGED` for a B2B shipment with a registered `webhook_url`: enqueue webhook delivery task
- Payload signed with HMAC-SHA256 using `B2bAccount.webhook_secret`; signature in `X-1DD-Signature` header
- Delivery with exponential backoff (1s, 2s, 4s, 8s, 16s); dead-letter after 5 failures; ops alert on dead-letter
- `WebhookDeliveryLog` records each attempt (status code, latency, error) — append-only

---

## Phase 6 — Resilience Hardening
> **Verifies the system degrades gracefully. Separate from Phase 3 circuit breakers which are
> basic availability guards. This phase covers the full failure matrix.**

---

### `M4 IMPL - PR #20 - Resilience hardening: bulkheads, fallbacks, and DLQ operator tooling`

**Module:** `orders`

**What:**
- Resilience4j bulkheads on `BookingService` to prevent thread pool exhaustion during M2/M3 spikes
- Razorpay webhook receiver hardening: signature validation + idempotent event handling for async payment confirmations and refund callbacks
- DLQ re-drive API: `POST /internal/v1/dlq/{messageId}/replay` — operator tool to re-drive parked Kafka messages
- `@Retryable` on `WebhookDeliveryService` with proper backoff strategy
- Chaos test: verify booking fails fast (< 600ms) when M2 circuit is open

---

## Phase 7 — Observability

---

### `M4 IMPL - PR #21 - Micrometer metrics, structured logging, and health indicators`

**Module:** `orders`

**What:**
- Micrometer metrics:
  - `m4.booking.duration` histogram (tagged by `customer_type`, `payment_mode`, `outcome`)
  - `m4.state_transition.count` counter (tagged by `from_state`, `to_state`)
  - `m4.kafka.consumer.lag` gauge per topic
  - `m4.idempotency.replay.count` counter
- Structured JSON logging: every log line includes `shipment_id`, `state`, `customer_type` as MDC fields
- Custom `HealthIndicator` for: DB connectivity, Kafka broker reachability, Redis ping
- `ERROR` log + metric when `parcel_id` is null at `PICKUP_ASSIGNED`
- `ERROR` log + metric when state machine receives an out-of-order event

---

## Deferred Technical Debt

Findings from architect reviews that were explicitly **not fixed** in the originating PR and need a dedicated future PR or team discussion. Each entry names the PR where it was found, the severity assigned at the time, and the recommended resolution.

---

### From PR #10 architect review (2026-05-30)

#### [DTD-10-A] `CRITICAL` — ETA network call inside the DB transaction

**File:** `BookingServiceImpl.java` → `persist()`, lines ~227–244

**Problem:** `etaPort.fetchEta(...)` is an outbound network call to M9. It executes inside the `TransactionTemplate.execute()` callback, meaning a DB connection is held open for the full round-trip to M9. Under concurrent bookings, this exhausts the connection pool if M9 is slow. The call is wrapped in `try/catch(Exception)` so it cannot roll back the booking, but the DB connection cost is real. This also violates the stated design invariant: "external calls must happen outside the DB transaction."

**Recommended fix:** Extract ETA from `persist()`. After `tx.execute()` commits, call ETA in a try/catch, then do a narrow second `tx.execute()` to write `eta_promised` and `sla_commitment_minutes` back to the shipment. Return `etaPromised = null` in the response if ETA fails (already the behaviour when the inner try/catch catches).

```java
// book() after the outer try/catch:
BookingResponse resp = tx.execute(status -> persist(...));  // no ETA inside

try {
    EtaResult eta = etaPort.fetchEta(new EtaRequest(...));
    tx.execute(status -> {
        Shipment s = shipmentRepository.findById(savedId).orElseThrow();
        s.setEtaPromised(eta.etaPromised());
        s.setSlaCommitmentMinutes((short) eta.slaCommitmentMinutes());
    });
    resp.setEtaPromised(eta.etaPromised());
    resp.setSlaCommitmentMinutes(eta.slaCommitmentMinutes());
} catch (Exception e) {
    log.warn("ETA fetch failed for shipment {}; booking proceeds without ETA", savedId, e);
}
```

**Target PR:** #20 (Resilience hardening) or a dedicated PR before #20.

---

#### [DTD-10-B] `CRITICAL` — Cross-module boundary violation: `IdempotencyFilter` imports `auth` internal class

**File:** `IdempotencyFilter.java:8`

**Problem:** `import com.oneday.auth.security.AuthUserDetails;` imports an internal class from the `auth` module. Per the design invariant: "cross-module imports: only import another module's public service interface, never its internal classes." If `auth` refactors `AuthUserDetails`, `orders` breaks silently. The companion test `IdempotencyFilterTest.java` also imports `com.oneday.auth.domain.User` directly.

**Recommended fix:** Define a thin `OneDayPrincipal` interface in `common` that exposes only what `orders` needs. Have `auth`'s `AuthUserDetails` implement it. `IdempotencyFilter` depends only on `common`.

```java
// common/src/main/java/com/oneday/common/security/OneDayPrincipal.java
public interface OneDayPrincipal {
    UUID getUserId();
    String getRole();
}

// auth: AuthUserDetails implements UserDetails, OneDayPrincipal { ... }

// IdempotencyFilter:
import com.oneday.common.security.OneDayPrincipal;
// ...
if (auth.getPrincipal() instanceof OneDayPrincipal p) {
    userId = p.getUserId().toString();
}
```

**Target PR:** #18 (Internal APIs) — when auth integration is formally wired into the orders module.

---

#### [DTD-10-C] `WARNING` — `@Transactional` on controller method hides missing service abstraction

**File:** `PickupOtpController.java:62` (`verifyOtp`)

**Problem:** `verifyOtp()` carries `@Transactional` at the controller level. This is load-bearing — without it, `pickupOtpService.verify()` (commits `used=true`) and `stateMachine.transition()` (commits state change) run in two separate transactions, leaving a window where a crash produces inconsistent state (OTP used, shipment still `PICKUP_ASSIGNED`). The `@Transactional` works at runtime (Spring proxies the controller) but violates the principle that business transactions belong in the service layer.

**Recommended fix:** Introduce `ShipmentPickupService.verifyOtpAndTransition(shipmentId, otp, ctx)` annotated `@Transactional`. The controller becomes a thin delegator with no `@Transactional` of its own.

**Target PR:** #13 (Cancellation) — when the pickup service layer is fleshed out.

---

#### [DTD-10-D] `WARNING` — State-gate business logic in `PickupOtpController`

**File:** `PickupOtpController.java:68`, `PickupOtpController.java:111`

**Problem:** Both `verifyOtp()` and `resendOtp()` check `shipment.getState() != ShipmentState.PICKUP_ASSIGNED` directly in the controller. Business invariants (which states allow OTP operations) belong in the service layer so they are enforced regardless of which entry point triggers the operation.

**Recommended fix:** Move the guard into `PickupOtpService` or the new `ShipmentPickupService` from DTD-10-C. Resolves together with that item.

**Target PR:** #13 (same as DTD-10-C).

---

#### [DTD-10-E] `WARNING` — Single shared `CircuitBreakerConfig` for all three circuit breakers

**File:** `ResilienceConfig.java:22`

**Problem:** `serviceability`, `pricing`, and `payment` circuit breakers are created from one `CircuitBreakerConfig`. Payment deserves a more conservative configuration (larger sliding window, lower failure-rate threshold) than a read-only serviceability check — a false trip on the payment CB means refusing money movement. Each CB is an independent state machine (named correctly), but their thresholds are identical.

**Recommended fix:** Register per-named configs in `CircuitBreakerRegistry` and add corresponding properties to `ResilienceProperties` (`paymentFailureRateThreshold`, `paymentSlidingWindowSize`, etc.). Mirrors what is already done for `TimeLimiterRegistry` (per-named timeout).

**Target PR:** #20 (Resilience hardening).

---

```
#1 (MutableBaseEntity + Flyway)
 └─► #2 (Kafka POJOs in common)
      └─► #3 (Port interfaces in common)
           ├─► [M2 implements PricingPort]        ← parallel, other team
           ├─► [M3 implements ServiceabilityPort] ← parallel, other team
           ├─► [M9 implements EtaPort]            ← parallel, other team
           ├─► [M8 implements BarcodePort]        ← parallel, other team
           └─► #4 (Flyway migrations)
                └─► #5 (JPA entities)
                     └─► #6 (Repositories)
                          └─► #7 (State machine)
                               ├─► #8 (Idempotency) ✅
                               └─► #9 (Ref gen + PaymentPort + PickupOtpService) ✅
                                    └─► #10 (B2C PREPAID booking + circuit breakers) 🔧
                                         ├─► #11 (COD path)         ← parallel
                                         ├─► #12 (B2B booking)      ← parallel
                                         └─► #13 (Cancellation)
                                              └─► #14 (Kafka producer)
                                                   ├─► #15 (Consumers: DA + Hub)
                                                   └─► #16 (Consumers: Scan + Flight + Cron + Exceptions)
                                                        ├─► #17 (Tracking API)   ← parallel
                                                        ├─► #18 (Internal APIs)  ← parallel
                                                        └─► #19 (B2B Webhooks)   ← parallel
                                                             └─► #20 (Resilience hardening)
                                                                  └─► #21 (Observability)
```

---

## What Other Teams Unlock at Each Phase

| After PR | Other teams can... |
|---|---|
| #1 | Write entity code in any module using `MutableBaseEntity`; write Flyway migrations in module-prefixed folders |
| #2 | Write Kafka producers and consumers using shared event POJOs and topic constants |
| #3 | M2 implements `PricingPort`; M3 implements `ServiceabilityPort`; M9 implements `EtaPort`; M8 implements `BarcodePort` |
| #7 | M10 (SLA) can build against the state machine's `STATE_CHANGED` events confidently |
| #10 | M5 can start building DA assignment against `shipment.created` events and the internal `GET /internal/v1/shipments` endpoint |
| #14 | All modules can verify their events are being consumed correctly by M4 |
| #18 | M7, M9, M10, M11 can call M4's internal endpoints during their own development |

---

## Testing Strategy

| Layer | Tool | When |
|---|---|---|
| Unit | JUnit 5 + Mockito | Every PR |
| Repository | `@DataJpaTest` + `@AutoConfigureTestDatabase(replace=NONE)` + `@Import(FlywayAutoConfiguration.class)` against **local PostgreSQL 16** (Docker/Testcontainers blocked by Docker Engine API version mismatch in dev environment) | PR #6 onwards |
| State machine | Parameterized JUnit5 (all 27×27 transition matrix — 841 parameterized + 108 named tests = 949 total in PR #7) | PR #7 |
| Kafka | `@EmbeddedKafka` | PR #14 onwards |
| API | `@SpringBootTest` + MockMvc + stub ports via `@TestConfiguration` | PR #10 onwards |
| Concurrency | `CountDownLatch` multi-thread test for B2B credit check | PR #12 |
| Resilience | Resilience4j test utilities + `WireMock` for M2/M3 | PR #10, #20 |
