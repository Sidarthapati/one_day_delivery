# M4 Events Lane — Implementation Plan (Person B)

Owner: Person B. Scope: the M4 Kafka **producer**, **consumers**, and **B2B webhooks** —
everything under `orders/events/` plus the minimal hooks into the write-path that fire the
events. Companion to `M4-IMPL-PLAN.md` (PRs #14, #15, #16, #19).

## Core principle

The events lane must **not** guess contracts for modules that do not exist yet.

- **Outbound events (producer)** are 100% M4-owned — the payloads already exist in
  `common/kafka/events/`. Safe to build now.
- **Inbound events (consumers)** are owned by the *producing* module (M5, M7, M8, M9, M6, M11).
  Those modules are largely unbuilt, so their contracts are not yet real. We **defer** consumers
  and co-design each one with its real producer when that module is built. When we do build a
  consumer, it reads **only** what it needs to drive a transition (`shipmentId` + the event-type
  enum, both already committed in `common`) and ignores everything else (`@JsonIgnoreProperties`).

## The decoupling seam

The state machine and booking flow stay ignorant of Kafka. They publish **in-process Spring
`ApplicationEvent`s**; the producer listens with `@TransactionalEventListener(AFTER_COMMIT)` and
emits to Kafka. `AFTER_COMMIT` guarantees no event fires for a rolled-back DB change.
`EventPublisher` is best-effort (a broker hiccup is logged, never thrown).

```
write-path (Person A)                 events lane (Person B, orders/events/)
─────────────────────                 ──────────────────────────────────────
ShipmentStateMachineImpl.transition()
   └─ publishEvent(ShipmentTransitioned) ──► ShipmentEventProducer.onShipmentTransitioned()
                                                 └─ EventPublisher.publish(SHIPMENTS_EVENTS, STATE_CHANGED)
BookingServiceImpl.persist()
   └─ publishEvent(ShipmentBooked) ────────► ShipmentEventProducer.onShipmentBooked()
                                                 └─ EventPublisher.publish(SHIPMENTS_EVENTS, CREATED)
```

There are exactly **two hooks into Person A's code**, both additive:
1. `ShipmentStateMachineImpl` — one `publishEvent` after the history save (STATE_CHANGED).
2. `BookingServiceImpl.persist()` — one `publishEvent` after the ETA block (CREATED).

## Status & phases

| # | Deliverable | Trigger / depends on | Status |
|---|-------------|----------------------|--------|
| 14a | `STATE_CHANGED` producer | state-machine transition (exists) | ✅ Done |
| 14b | `CREATED` producer | shipment birth in `BookingServiceImpl` (#10/#11) | ✅ Done |
| 14c | rich `CANCELLED` producer | cancellation flow (PR #13) | ✅ Done |
| 15/16 | All 6 consumers (DA, Hub, Scan, Flight, Cron, Exceptions) | upstream modules (not built) | ✅ Scaffolded, **dormant** (`autoStartup=false`) |
| 19 | B2B webhooks (HMAC) | B2B booking (**PR #12 — not built**) | ⏸ Parked |
| 17/18 | help on read APIs (optional) | Person A's lane | ⚪ Optional |

### Phase 1 — Producer (now)
- **14a STATE_CHANGED — done.** `ShipmentTransitioned` + `ShipmentEventProducer` + hook in
  `ShipmentStateMachineImpl`. Fires on every committed transition.
- **14b CREATED — done.** `ShipmentBooked` + listener in `ShipmentEventProducer` + hook in
  `BookingServiceImpl.persist()`. Lat/lon left null (not stored on `Address`); address line from
  `Address.line1`.
- **14c CANCELLED — done (PR #13).** `CancellationServiceImpl` fires an in-process `ShipmentCancelled`
  event (cancelledAtState, reason, refundInitiated, refundAmountPaise); `ShipmentEventProducer`'s
  AFTER_COMMIT listener maps it to the rich `ShipmentCancelledEvent` on `oneday.shipments.events`.
  The plain `→ CANCELLED` STATE_CHANGED still fires too (both are emitted).

### Phase 2 — Consumers (built, dormant)
All six consumers exist in `orders/events/` (`DaEventsConsumer`, `HubEventsConsumer`,
`ScanEventsConsumer`, `FlightEventsConsumer`, `CronEventsConsumer`, `ExceptionsEventsConsumer`),
each reading a minimal `*Event` DTO from `common/kafka/events/` (`shipmentId` + the event-type
enum, `@JsonIgnoreProperties(ignoreUnknown=true)`). Each switches on the enum and calls
`stateMachine.transition(id, TARGET, TransitionContext.fromKafka(...))`. Mappings are taken
verbatim from `M4-INTEGRATION-CONTRACTS.md` (§ consumer tables).

`autoStartup=false` keeps them dormant until the producing module exists; flip
`orders.kafka.consumer.auto-startup=true` to enable.

**Deferred inside the consumers (TODOs), per "base flow first":**
- `DaEventType.PICKUP_ASSIGNED` — pickup-OTP generation/send.
- `ScanEventType.HUB_ORIGIN_IN` / `SELF_DROP_ACCEPTED` — ETA recalculation + customer notification.
- `ScanEventType.LABEL_GENERATED` — not a transition; sets `shipment.parcel_id`. Needs `ScanEvent`
  extended with the `parcelId` field; currently a no-op.
- `HubEvent` HUB_COLLECT path (**OD-9 open**) — no event type exists yet; no handler.

**Integration wiring to finalize when a real producer lands (the only thing not provable now):**
JSON is snake_case on the wire (`shipment_id`); the consumer `JsonDeserializer` must map
snake_case → DTO fields and resolve the producer's class/type-header. A 5-minute config step at
integration, gated behind `autoStartup=false`.

### Phase 3 — Webhooks (#19)
After B2B booking (#12). Consumes the outbound shipment events; HMAC-signs and POSTs to the
B2B account's `webhook_url`/`webhook_secret`.

## Testing posture
- No hosted broker needed. Unit tests mock `EventPublisher`; broker-level confidence comes from
  one `@EmbeddedKafka` round-trip test (needs `spring-kafka-test` as a test dep). Deferred for now.
- Local end-to-end (Redpanda container) only becomes useful once the app boots cleanly and a
  consumer is wired — not yet.

## Notes / coordination
- Migration range: PR #11 took `V4_12`. If the events lane ever needs a migration, start at `V4_13`.
- The two write-path hooks are the only coupling to Person A — keep them additive and flag them in review.
