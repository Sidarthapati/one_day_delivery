# Event Bus Architecture — RabbitMQ

| Field | Value |
|-------|-------|
| **Status** | Design / convention — applies to **every** module (M3–M11) |
| **Decision** | Inter-module events run over **RabbitMQ** (publish-direct, no DB outbox). Cheap, push-based (low latency), simple. |
| **Migration** | ✅ **DONE (2026-06-17).** The whole repo was migrated off Kafka to RabbitMQ — §10 records exactly what changed. Build green: common/grid/routing/orders compile + unit tests pass; no `spring-kafka` / `org.springframework.kafka` references remain. |
| **Portability** | All business code depends only on two ports — `EventPublisher` + `EventHandler`. The broker is an adapter. Swapping RabbitMQ for Kafka/SQS later, or bolting on a DB outbox for stronger delivery guarantees, is an adapter change — **zero business-code change.** |
| **Accepted trade-off** | Publish-direct means a crash between DB-commit and publish can lose an event (no outbox). Mitigated by idempotent handlers + a reconciliation sweep. Add the outbox later (§11) if loss ever bites. |

---

## 1. The one principle

> **Business code depends only on two interfaces — `EventPublisher` (produce) and `EventHandler`/`@RabbitListener` (consume). The broker lives behind them.**

Producers never see a `RabbitTemplate`; they call `publish(stream, event)`. Consumers express business logic in a handler; the RabbitMQ wiring (exchange/queue/binding) is declared once per consumer, separately. This is ports-and-adapters applied to messaging — the reason a future broker swap is cheap.

---

## 2. How RabbitMQ works — what we need to know

RabbitMQ is a **broker/queue**, not a log. The mental model is different from Kafka in exactly one place — **there are no consumer groups** — so read this section before writing any messaging code.

### 2.1 The three moving parts

```
   PRODUCER ──publish──▶ EXCHANGE ──routes copies──▶ QUEUE ──delivers──▶ CONSUMER
                       (a router)   (by binding)   (a mailbox)         (your handler)
```

- **Exchange** — where producers send. It holds nothing; it just *routes* each message to zero or more queues based on **bindings**. (≈ a Kafka topic, but it's a router, not storage.)
- **Queue** — a durable mailbox that *holds* messages until a consumer reads and **acks** them. Once acked, the message is gone from that queue. **This is the unit that replaces the Kafka consumer group.**
- **Binding** — the rule connecting an exchange to a queue (optionally filtered by a **routing key**).

### 2.2 The Kafka → RabbitMQ concept map (the important table)

| Kafka | RabbitMQ | What it means for us |
|-------|----------|----------------------|
| Topic | **Exchange** | the named thing a producing module publishes to |
| Consumer **group** | **Queue** | one independent reader of a stream. *No consumer-group concept exists — a queue plays that role.* |
| N consumers in one group (load-balanced) | N consumer instances on **one queue** (*competing consumers*) | scale a module horizontally — RabbitMQ round-robins messages across them |
| N consumer groups (each gets a full copy / fan-out) | **N separate queues** bound to the same exchange | each consuming module declares **its own queue** → each gets its own copy |
| Partition key | **Routing key** | we set it to the event type, so a queue can filter which event types it wants |
| Offset commit | **Message ack** | progress = "acked & removed", not "offset advanced" |
| Topic retention / replay | *(none — acked messages are deleted)* | no replay; the DB state is the system of record |

**The one rule to internalise:** *a Kafka consumer group = a RabbitMQ queue.* Want two modules to both receive an event? Give each its own queue bound to the exchange. Want to scale one module across 3 instances? Point all 3 at the same queue.

### 2.3 The order flow in RabbitMQ — order → M4 → M5 → M6

Same flow you know, shown with exchanges and queues:

```
                          exchange: oneday.shipments.events
  ┌─────┐  publish          (topic)
  │ M4  │ ─ CREATED ───────────▶ ┬──────▶ queue: dispatch.shipments ──▶ [M5 consumer]
  └─────┘                        │
   (orders)                      └──────▶ queue: sla.shipments      ──▶ [M10 consumer]   ← fan-out:
                                                                                            two queues,
                                                                                            two copies
        M5 assigns a DA, acks its message, then publishes:

                          exchange: oneday.da.events
  ┌─────┐  publish          (topic)
  │ M5  │ ─ DROP_ASSIGNED ─────▶ ┬──────▶ queue: routing.da ─────────▶ [M6 consumer]
  └─────┘                        │
  (dispatch)                     └──────▶ queue: orders.da  ─────────▶ [M4 consumer drives state machine]
```

Step by step:

1. **Customer books** → M4 creates the shipment and **publishes** `ShipmentCreatedEvent` to the exchange `oneday.shipments.events` (routing key = `CREATED`).
2. The exchange **copies** the message into every bound queue. M5's queue `dispatch.shipments` gets a copy; M10's queue `sla.shipments` gets its own copy. *(That's fan-out — the consumer-group equivalent: each module has its own queue.)*
3. **M5 consumer** reads from `dispatch.shipments`, assigns a DA, and **acks** (message removed from M5's queue — M10's copy is untouched). M5 then **publishes** `DaEvent(DROP_ASSIGNED)` to `oneday.da.events`.
4. **M6 consumer** reads from its queue `routing.da`, binds the parcel to a loop, acks. (M4 also has a queue `orders.da` on the same exchange to drive its state machine — again, separate queue = separate copy.)

If M6 ran 3 instances for load, all 3 would read from the **one** `routing.da` queue and RabbitMQ would round-robin messages between them (competing consumers). No groups, no partitions to think about.

### 2.4 The few other things we must configure

- **Exchange type = `topic`** for all our exchanges: routing key = `event.eventTypeName()`, so a queue can bind `#` (all types) or a pattern (e.g. `PICKUP_*`) to filter. (`fanout` = everyone gets everything; `direct` = exact match. We use `topic` for flexibility.)
- **Durability** — durable exchanges + durable queues + persistent messages, so a broker restart doesn't lose in-flight events.
- **Acks** — Spring's default is auto-ack-on-success: the listener returns ⇒ ack; it throws ⇒ nack ⇒ retry/dead-letter. At-least-once ⇒ **handlers must be idempotent**.
- **Dead-letter exchange (DLX)** — a poison message that keeps failing is routed to a `*.dlq` queue after N retries (the RabbitMQ equivalent of today's `KafkaErrorConfig` DLQ behaviour). It must never block its queue.
- **Prefetch (QoS)** — how many unacked messages one consumer holds at once; keep it small (e.g. 10) so work spreads fairly across competing consumers.

---

## 3. Dependencies

### 3.1 Maven (replaces `spring-kafka` in `common`, `routing`, `grid`)

```xml
<!-- main: brings spring-rabbit + amqp-client + Boot auto-config -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>

<!-- test -->
<dependency>
    <groupId>org.springframework.amqp</groupId>
    <artifactId>spring-rabbit-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>rabbitmq</artifactId>      <!-- spins a real broker for integration tests -->
    <scope>test</scope>
</dependency>
```

`orders` (and any module that only consumes) gets `spring-rabbit` transitively via its dependency on `common` — no extra entry needed there, just as it gets `spring-kafka` today.

### 3.2 Infra

- **Managed broker:** **CloudAMQP** (hosted RabbitMQ) — has a free *Little Lemur* tier for dev and cheap paid tiers for prod. One instance for the shared dev env, credentials in `.env` (gitignored), same place the Kafka/Render creds live today.
- **Local dev:** Docker — `docker run -d --name rabbit -p 5672:5672 -p 15672:15672 rabbitmq:3.13-management`. The management UI at `http://localhost:15672` (guest/guest) lets you watch exchanges, queues, and message rates — very useful while building.

### 3.3 Config (`application.yml`) — replaces the `spring.kafka.*` block

```yaml
spring:
  rabbitmq:
    host: ${RABBIT_HOST:localhost}
    port: ${RABBIT_PORT:5672}
    username: ${RABBIT_USER:guest}
    password: ${RABBIT_PASS:guest}
    ssl:
      enabled: ${RABBIT_SSL:false}        # true for CloudAMQP
    listener:
      simple:
        acknowledge-mode: auto            # ack on success, nack→retry/DLX on throw
        prefetch: 10
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000ms        # mirrors today's KafkaErrorConfig: 3 tries, 1s apart
```

---

## 4. Core contracts (in `common`)

### 4.1 `DomainEvent` — already present, keep

```java
public interface DomainEvent {
    UUID    eventId();        // idempotency key for consumers (Rabbit is at-least-once)
    String  eventTypeName();  // → used as the routing key
    String  partitionKey();   // entity id (parcel/shipment) — informational under Rabbit
    Instant occurredAt();
}
```

### 4.2 `EventPublisher` — the producer port (make the existing class an interface)

```java
public interface EventPublisher {
    /** stream = an exchange name (an EventStreams constant). */
    void publish(String stream, DomainEvent event);
}
```

Producers already inject this and call `publish(EventStreams.SHIPMENTS_EVENTS, event)` — **call sites don't change**, only the bean behind it does.

### 4.3 `EventStreams` — rename of `KafkaTopics` (values are now exchange names)

The constants stay (`oneday.shipments.events`, `oneday.da.events`, …); they name **exchanges** now instead of topics. Rename the class so the code reads honestly.

---

## 5. Producer adapter — `RabbitEventPublisher`

```java
@Component
class RabbitEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);
    private final RabbitTemplate rabbit;

    RabbitEventPublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; }

    @Override
    public void publish(String exchange, DomainEvent event) {
        try {
            // exchange = stream; routing key = event type so queues can filter
            rabbit.convertAndSend(exchange, event.eventTypeName(), event);
        } catch (Exception e) {   // best-effort, exactly like today's EventPublisher
            log.warn("Rabbit publish failed — exchange={} type={}: {}",
                    exchange, event.eventTypeName(), e.getMessage());
        }
    }
}
```

A single Jackson converter bean makes the payload JSON (drop-in for the JsonSerializer used with Kafka today):

```java
@Bean Jackson2JsonMessageConverter jsonConverter(ObjectMapper m) {
    return new Jackson2JsonMessageConverter(m);
}
```

---

## 6. Consumer — handler + topology

A consumer is two things: the **listener** (transport edge) and the **topology** (exchange/queue/binding it reads).

### 6.1 The listener (this replaces each `@KafkaListener`)

```java
@Component
class DaEventsListener {

    private final ShipmentStateMachine stateMachine;          // same deps as today's DaEventsConsumer
    private final PickupOtpService pickupOtpService;

    @RabbitListener(queues = "orders.da")                     // a queue this module owns
    public void onDaEvent(DaEvent event) {
        // EXACTLY the body of today's DaEventsConsumer.onDaEvent — must be idempotent
        ...
    }
}
```

### 6.2 The topology (declare each queue + binding once)

```java
@Configuration
class DaEventsTopology {
    @Bean TopicExchange daExchange() {                        // shared by all consumers of this stream
        return ExchangeBuilder.topicExchange(EventStreams.DA_EVENTS).durable(true).build();
    }
    @Bean Queue ordersDaQueue() {                             // THIS module's queue (= its "consumer group")
        return QueueBuilder.durable("orders.da")
                .deadLetterExchange(EventStreams.DA_EVENTS + ".dlx")   // poison → DLX
                .build();
    }
    @Bean Binding ordersDaBinding() {
        return BindingBuilder.bind(ordersDaQueue()).to(daExchange()).with("#");  // all event types
    }
}
```

> **Naming convention:** exchange = the `EventStreams` constant; queue = `<consumer-module>.<stream-short>` (`orders.da`, `routing.da`, `sla.shipments`). One queue per (consuming module, stream). To filter event types, bind a routing-key pattern instead of `#` (e.g. `.with("PICKUP_*")`).

### 6.3 Dead-letter wiring (one helper, replaces `KafkaErrorConfig`)

Declare, per stream, a `<stream>.dlx` exchange + a `<stream>.dlq` queue bound to it. Spring's listener retry (configured in §3.3) re-tries 3× then routes the message to the DLX → it lands in the `.dlq` queue for inspection/replay. Same behaviour as the current Kafka DLQ, expressed as Rabbit topology.

---

## 7. Delivery semantics / discipline (every module)

1. **At-least-once ⇒ idempotent handlers.** A redelivery (consumer crashed before ack) must be a no-op the second time. Dedupe on `eventId`, or rely on a natural no-op ("already in target state").
2. **Self-contained payloads.** Act from the event alone — never JOIN into another module's tables. (This is the rule that keeps modules decoupled and a future DB-split painless.)
3. **No synchronous reply over the bus.** Need a response? That's an HTTP query, not an event.
4. **Durable everything** (exchanges, queues, messages) so a broker restart doesn't drop in-flight events.
5. **Accept the publish-direct trade-off knowingly:** an event can be lost if the app dies between DB-commit and publish. Backstop = a periodic reconciliation sweep (e.g. "shipments stuck in PICKED_UP with no downstream event") + the option to add an outbox (§11).

---

## 8. Naming conventions (all modules)

- **One exchange per producing module**, named by an `EventStreams` constant (`SHIPMENTS_EVENTS`, `DA_EVENTS`, `CRON_EVENTS`, `SCAN_EVENTS`, …). Type = `topic`.
- **Routing key = the event-type enum value** (`CREATED`, `DROP_ASSIGNED`, `DA_CRON_SCHEDULED`, …).
- **One queue per (consuming module, exchange)**, named `<module>.<stream-short>`; durable; with a DLX.
- **Scans are M8's exchange** (`SCAN_EVENTS`) — not duplicated onto a producer's own exchange.

---

## 9. Per-module authoring checklist

**To produce:** inject `EventPublisher`; `publish(EventStreams.X, event)` after the state it describes is committed. (Event POJO lives in `common.kafka.events`, carries `eventId` + `eventTypeName`.)

**To consume:** (1) write a `@RabbitListener` method holding the business logic, idempotent; (2) add a small `@Configuration` declaring the exchange (shared), this module's queue, and the binding. Done.

**Never:** inject `RabbitTemplate` in business code, JOIN into another module's tables to handle an event, or assume replay/history exists.

---

## 10. Migration from the Kafka wiring → RabbitMQ — ✅ DONE (2026-06-17)

This was executed across the repo. Touch points and what changed:

| # | Change | Files |
|---|--------|-------|
| 1 | Swap `spring-kafka` → `spring-boot-starter-amqp` (+ test deps) | `common/pom.xml`, `routing/pom.xml`, `grid/pom.xml` |
| 2 | Rename `KafkaTopics` → `EventStreams` (values unchanged; they're exchange names now) | `common/.../kafka/KafkaTopics.java` + all imports |
| 3 | Turn `EventPublisher` into an interface; move the body into `RabbitEventPublisher` (`convertAndSend(exchange, type, event)`) | `common/.../kafka/EventPublisher.java` |
| 4 | Replace `KafkaErrorConfig` (DLQ recoverer) with Rabbit retry config (§3.3) + per-stream DLX/DLQ topology beans | `common/.../kafka/KafkaErrorConfig.java` |
| 5 | Each consumer: `@KafkaListener(topics=…)` → `@RabbitListener(queues=…)`, add a topology `@Configuration` (exchange+queue+binding). Drop `groupId`/`autoStartup`; "dormant until producer exists" is naturally handled — the queue just sits empty. | orders: `DaEventsConsumer`, `HubEventsConsumer`, `ScanEventsConsumer`, `FlightEventsConsumer`, `CronEventsConsumer`, `ExceptionsEventsConsumer`; grid: `TileQueueDepthConsumer` |
| 6 | Grid producers — already call `EventPublisher`; no change beyond the rename | `grid: TileOverloadAlertProducer`, `NoDaAlertProducer` |
| 7 | M4 producers: keep `@TransactionalEventListener(AFTER_COMMIT)` (publish-direct), just via the Rabbit `EventPublisher` | `orders/.../ShipmentEventProducer` |
| 8 | Config — replace the `spring.kafka.*` block with `spring.rabbitmq.*` (§3.3); add `RABBIT_*` env vars; update `application-dev.yml` for CloudAMQP SASL/SSL | `app/src/main/resources/application.yml`, `application-dev.yml`, `.env` |
| 9 | Tests — `EventPublisherTest` + grid producer tests: mock `RabbitTemplate` instead of `KafkaTemplate`; add Testcontainers-RabbitMQ for any integration test | `common`, `grid` test sources |
| 10 | Provision the CloudAMQP instance + local Docker broker; remove Kafka cluster provisioning | infra / `docs/DEPLOYMENT-SETUP.md` |

After this, the whole app runs on RabbitMQ; M4's 6 consumers + grid's producers/consumer work unchanged in logic.

---

## 11. Keeping the door open (cheap insurance)

Two things make RabbitMQ-direct *not* a one-way door:

1. **The ports** (`EventPublisher` / `EventHandler`-style listeners). If you later want stronger delivery, add an **outbox**: producers write events to an `event_log` table inside their transaction, and a small **relay** tails it and calls `RabbitTemplate`. Producers and handlers don't change — only the publisher adapter does. (This is the standard fix for the publish-direct loss window, addable the day it matters.)
2. **Idempotent handlers + `eventId`.** Required for Rabbit anyway, and exactly what makes an outbox (or a broker swap to Kafka/SQS) safe to introduce later.

---

## 12. Anti-patterns

| Anti-pattern | Why it hurts | Do instead |
|--------------|--------------|------------|
| `RabbitTemplate` / `@RabbitListener` logic in business services | welds logic to the broker | publish via `EventPublisher`; keep listener bodies thin (delegate to a service method) |
| One shared queue for multiple modules | they'd steal each other's messages (competing consumers) | **one queue per consuming module** |
| Consumer JOINs into the producer's tables | re-couples modules | self-contained event payloads |
| Non-idempotent handler | at-least-once redelivery double-acts | dedupe on `eventId` |
| Assuming you can replay history | Rabbit deletes on ack — there is none | DB state is the system of record; add an outbox if you need a log |
| Non-durable queue/exchange | broker restart drops events | durable + persistent everywhere |

---

*Bottom line: RabbitMQ — producers publish to a per-module **exchange**, each consuming module owns a **queue** bound to it (the consumer-group replacement), messages are acked and removed (no replay). Cheap, low-latency, simple. The `EventPublisher`/listener ports keep a future outbox or broker swap to an adapter change, not a rewrite.*
