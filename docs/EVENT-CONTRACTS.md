# Event Contracts (Kafka)

The single source of truth for every Kafka topic, event type, producer, and consumer.
**Append a row here whenever you add an event** — this doc *is* the cross-module contract.

Transport: Kafka API (Redpanda). Engine is identical across environments — only the
connection string + credentials change (see [Environments](#environments)).

---

## Conventions (read before adding anything)

1. **One topic per producing module** — `oneday.<module>.events`. Not one topic per event type.
2. **Event type is a field**, not a topic — a per-module enum (`GridEventType`, `DaEventType`, …)
   carried in the payload. Consumers `switch` on it.
3. **Message key = the entity the event is about** (`shipmentId`, `tileId`). Same key ⇒ same
   partition ⇒ ordered per entity. This is `DomainEvent.partitionKey()`.
4. **Evolve additively only.** Never rename/remove a field — add new optional ones. That's what
   `schemaVersion` is for. Old consumers must keep working when you add a field.
5. **One consumer group per module** (`groupId = "<module>"`). Each group reads independently and
   tracks its own offset; adding a consumer never affects existing ones.
6. **DLQ = `<topic>.dlq`.** A record that fails processing after 3 retries lands there
   (see [Failure handling](#failure-handling)).

### Where code lives — contract vs behaviour

| Goes in `common` (shared contract) | Stays in the owning module (behaviour) |
|---|---|
| Event payloads (POJOs/records implementing `DomainEvent`) | The producer call / `EventPublisher` usage |
| Event-type enums | The `@KafkaListener` consumers |
| `KafkaTopics` constants | The state machine / business rules |
| `DomainEvent`, `EventPublisher`, `KafkaErrorConfig` (generic infra) | Module-specific config & group ids |

> A payload in `common` is correct even if only one module *produces* it, because consumers that
> must **not** depend on the producer module (e.g. M3 grid depends only on `common`) still need the
> class to deserialize it. Logic (a producer `@Component`, a `@KafkaListener`, `KafkaTemplate`
> wiring) in `common` is the line being crossed — that belongs in the module.

---

## Topic & event registry

| Topic | Producer | Event types (enum) | Consumers |
|---|---|---|---|
| `oneday.shipments.events` | M4 Orders | `CREATED`, `STATE_CHANGED`, `CANCELLED` | M5, M8, M10 |
| `oneday.grid.events` | M3 Grid | `NO_DA_ALERT`, `TILE_OVERLOAD_ALERT`, `ASSIGNMENT_UPDATED`¹ | M5, M10, M11 |
| `oneday.da.events` | M5 Dispatch | `PICKUP_ASSIGNED`, `PICKUP_COMPLETED`, `PICKUP_FAILED`, `VAN_HANDOFF_COMPLETED`, `DROP_ASSIGNED`, `DROP_COLLECTED`, `DROP_COMPLETED`, `DROP_FAILED` | M4, M8, M10, M11 |
| `oneday.cron.events` | M6 Routing | `DEPARTED_HUB`, `DEPARTED_AIRPORT` | M4, M9, M10 |
| `oneday.hub.events` | M7 Hub | `STAND_ASSIGNED`, `BAG_CREATED`, `SAMECITY_OUTBOUND`, `DEST_SORT_COMPLETE`, `DROP_VAN_HANDOFF` | M4, M8, M9, M10 |
| `oneday.scan.events` | M8 Barcode | `HUB_ORIGIN_IN`, `SELF_DROP_ACCEPTED`, `GHA_ACCEPTANCE`, `HUB_DEST_IN`, `LABEL_GENERATED`, `HUB_COLLECT_COMPLETED` | M4, M7, M10 |
| `oneday.flight.events` | M9 Airline | `DEPARTED`, `LANDED`, `RTO_IN_TRANSIT` | M4, M7, M10, M11 |
| `oneday.exceptions.events` | M11 Exceptions | `RTO_INITIATED`, `PICKUP_RESCHEDULED`, `DELIVERY_RESCHEDULED`, `RTO_COMPLETED` | M4, M5, M6, M10 |
| `oneday.notifications.requested` | many | command topic (single consumer) | notification service |
| `oneday.<topic>.dlq` | infra | dead-lettered records | ops / manual replay |

¹ `ASSIGNMENT_UPDATED` reserved in the enum; not emitted yet (future `ProposalServiceImpl.approve()`).

**Build status:** M3 produces today; M1/M4 scaffolding exists; M5–M11 are the agreed contract to
build against. Status: only M3 + partial M4 are wired. Consumers attach as their module is built
(dormant `autoStartup=false` until their producer is live).

---

## Using the spine

**Produce** — implement `DomainEvent` on the payload, inject `EventPublisher`:

```java
public record CronDepartedEvent(CronEventType eventType, UUID shipmentId, UUID vanId, Instant occurredAt)
        implements DomainEvent {
    public String partitionKey()  { return shipmentId.toString(); }
    public String eventTypeName() { return eventType.name(); }
}

// in M6:
eventPublisher.publish(KafkaTopics.CRON_EVENTS,
        new CronDepartedEvent(DEPARTED_HUB, shipmentId, vanId, Instant.now()));
```

**Consume** — subscribe to the producer's topic, `switch` on the type:

```java
@KafkaListener(topics = KafkaTopics.DA_EVENTS, groupId = "orders")
public void onDaEvent(DaEventEnvelope e) {
    switch (e.eventType()) {
        case PICKUP_ASSIGNED  -> stateMachine.toPickupAssigned(e.shipmentId());
        case PICKUP_FAILED    -> stateMachine.toPickupFailed(e.shipmentId());
        default               -> { /* ignore types this module doesn't care about */ }
    }
}
```

**Test (no broker)** — mock `KafkaTemplate` and assert the send; see `EventPublisherTest` as the
template. For wiring/serde tests use `@EmbeddedKafka` (in-JVM, no Docker).

---

## Failure handling (DLQ)

`KafkaErrorConfig` installs one error handler for all listeners: a throwing consumer retries
**3× (1s apart)**, then the record is published to **`<topic>.dlq`** and the consumer moves on, so a
poison message never blocks its partition. Inspect/replay the DLQ later.

Caveats:
- DLQ topics may need to exist first. On a managed cluster with auto-create disabled, pre-create
  `oneday.<topic>.dlq` for each topic you consume.
- The recoverer keys the dead-lettered record the same way, preserving per-entity order in the DLQ.

---

## How to hand-produce a test event

To test a consumer in isolation you don't need its upstream module — drop the event yourself.

**Redpanda Console** (web UI) → pick the topic → *Produce message* → set key + paste JSON.

**`rpk` CLI:**
```bash
echo '{"eventType":"NO_DA_ALERT","cityId":"...","tileId":"...","validDate":"2026-05-30","reason":"NO_DA_AVAILABLE","alertedAt":"2026-05-30T10:00:00Z"}' \
  | rpk topic produce oneday.grid.events --key "<tileId>"
```
Then watch your locally-running consumer's logs react. Two windows to debug with: your **app logs**
("did my code run?") and the **cluster console** ("is the message on the topic / consumer lag?").

---

## Environments

Code is identical across all three; only `bootstrap-servers` + creds differ (Spring profile).

| Environment | Broker | Profile / config |
|---|---|---|
| Unit tests | none (mock `KafkaTemplate`) | — |
| Integration tests | `@EmbeddedKafka` (in-JVM) | test scope |
| Team testing | managed Redpanda Serverless | `-Dspring.profiles.active=dev` + `KAFKA_BOOTSTRAP_SERVERS`/`KAFKA_KEY`/`KAFKA_SECRET` (see `app/application-dev.yml`) |
| Production (later) | managed or single-node Redpanda | env vars only |

Topic names are code constants (`KafkaTopics`), identical in every environment — not configured
per-env unless you later prefix them to isolate devs on a shared cluster.
