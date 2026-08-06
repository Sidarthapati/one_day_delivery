# OneDay — RabbitMQ Event Map

The platform's modules are decoupled over **RabbitMQ (CloudAMQP)**. This is the single reference for
every exchange, the events on it, who produces/consumes them, and what's live today vs. waiting on an
unbuilt module. It ends with a **business scenario traced through the code**.

> All event contracts live in the `common` module (`com.oneday.common.kafka.events` /
> `…kafka.enums`). A few module-local payloads live under `<module>/events/payload`.

## Conventions

- **One exchange per producing module** (topic exchange). Each consuming module binds **one durable
  queue with routing key `#`** (catch-all) and dispatches in code by event type — never keyed bindings.
  (Standing design rule: split a queue only on real volume/failure skew.)
- **Type resolution:** the producer stamps a Jackson `__TypeId__` header (`DefaultClassMapper`); the
  consumer deserializes to the mapped class. A wrong/mismatched type **lands in the queue's `.dlq`**
  rather than deserializing — a non-empty `*.dlq` is the first signal of a contract break.
- **Ordering:** one queue per exchange keeps a given aggregate's events in order; the listener takes a
  supertype param (e.g. `BaseShipmentEvent`) and switches on `eventType`.
- **Publish timing:** state-change producers publish `AFTER_COMMIT` (the DB row exists before anyone
  reacts).

## Status legend

| | Meaning |
|---|---|
| 🟢 **Live** | producer **and** consumer running today |
| 🟡 **Consumer-ready** | queue bound + consumer coded, but the **producer module isn't built** (queue is dormant; some are faked by the demo) |
| ⚪ **Defined** | exchange/contract exists, nothing wired yet |

---

## The map

### 🟢 `oneday.shipments.events` — M4 (orders)
| | |
|---|---|
| Events | `ShipmentCreatedEvent` (CREATED), `ShipmentStateChangedEvent` (STATE_CHANGED), `ShipmentCancelledEvent` — all extend `BaseShipmentEvent` |
| Producer | orders `ShipmentEventProducer` (`@TransactionalEventListener` AFTER_COMMIT) |
| Consumer | dispatch `ShipmentEventsConsumer` → queue **`m5.shipments`** |
| Action | CREATED ⇒ `DispatchService.assignPickup`; CANCELLED ⇒ `cancelTask`; STATE_CHANGED(`HANDED_TO_DROP_VAN`) ⇒ `assignDelivery` — **Q-M4-2 resolved**: M4 enriches that one transition with `destLat/destLon/destTileId/dropType`; idempotent (an already-active DELIVERY task is kept) |

### 🟢 `oneday.da.events` — M5 (dispatch)
| | |
|---|---|
| Event | `DaLifecycleEvent` (unified; routing key = `eventType`) |
| Types | `PICKUP_ASSIGNED, PICKUP_COMPLETED, PICKUP_FAILED, VAN_HANDOFF_COMPLETED, DROP_ASSIGNED, DROP_COLLECTED, DROP_COMPLETED, DROP_FAILED, QUEUE_REORDERED, DA_ABSENT, CRON_MISSED, COD_COLLECTED` |
| Producer | dispatch `DaEventProducer` (+ the demo run emits `PICKUP_COMPLETED`) |
| Consumers | orders `DaEventsConsumer` → **`orders.da`** (drives M4 states; mints pickup OTP on PICKUP_ASSIGNED) · routing `DaFeedConsumer` → **`routing.da`** (acts on `PICKUP_COMPLETED` → bind collect) |

### 🟢 `oneday.cron.events` — M6 (routing)
| | |
|---|---|
| Event | `CronEvent` |
| Types | `DA_CRON_SCHEDULED, SHUTTLE_SCHEDULED, ROUTE_PLAN_PUBLISHED, ROUTE_CHANGED, VAN_ARRIVED, VAN_RUNNING_LATE, HANDOFF_COMPLETED, HANDOFF_DISCREPANCY, LOOP_OVERFLOW` |
| Producers | routing `CronEventProducer`, `RouteDeviationProducer` (VAN_ARRIVED / VAN_RUNNING_LATE) |
| Consumers | dispatch `DaCronScheduledConsumer` → **`m5.cron`** (DA_CRON_SCHEDULED → store meeting times) · orders `CronEventsConsumer` → **`orders.cron`** (per-shipment custody transitions) |

### 🟢 `orders.tile_queue_depth` — M5 → M3 demand feedback
| | |
|---|---|
| Event | `TileQueueDepthEvent` · Producer dispatch `TileQueueDepthPublisher` (batch) · Consumer grid `TileQueueDepthConsumer` → **`grid.tile-queue-depth`** |

### 🟢 `oneday.grid.events` — M3 (grid) *(produced; no consumer yet)*
| | |
|---|---|
| Events | `NoDaAlertEvent` (NO_DA_ALERT), `TileOverloadAlertEvent` (TILE_OVERLOAD_ALERT) |
| Producers | grid `NoDaAlertProducer`, `TileOverloadAlertProducer` · Consumer: none yet → ops / M11 will subscribe |

---

### 🟡 `oneday.hub.events` — M7 (hub — not built)
| | |
|---|---|
| Events | `HubEvent` types `STAND_ASSIGNED, BAG_CREATED, SAMECITY_OUTBOUND, DEST_SORT_COMPLETE` + per-parcel `ParcelSortedForDeliveryEvent` (`PARCEL_SORTED_FOR_DELIVERY`, provisional, in **`common`**, implements `DomainEvent`) |
| Producer | **M7 (upcoming)** — today the **demo** emits `ParcelSortedForDelivery` in its place (the "Dispatch drops" button → `DemoDaController.dispatchDrops`) |
| Consumers (live) | routing `HubFeedConsumer` → **`routing.hub`** (sorted-for-delivery → `VanManifestService.bindDelivery`) · orders `HubEventsConsumer` → **`orders.hub`** (takes the `DomainEvent` supertype; acts on `HubEvent`, **ignores** foreign payloads like `ParcelSortedForDelivery` instead of DLQ-ing them) |
| Contract note | M7-D-002: M7 will emit the exact same `(parcelId, cityId, destinationHexId, validDate, sortedAt, slaDeadline)` shape → no change to M6's binder when it lands. |

### 🟡 `oneday.scan.events` — M8 (barcode — not built)
| | |
|---|---|
| Event | `ScanEvent` · Types `HUB_ORIGIN_IN, SELF_DROP_ACCEPTED, GHA_ACCEPTANCE, HUB_DEST_IN, LABEL_GENERATED` |
| Producer | M8 (upcoming) · Consumer (dormant) orders `ScanEventsConsumer` → **`orders.scan`** (scan → M4 transitions) |

### 🟡 `oneday.flight.events` — M9 (airline — not built)
| | |
|---|---|
| Event | `FlightEvent` · Types `DEPARTED, LANDED` |
| Producer | M9 (upcoming) · Consumers (dormant) orders `FlightEventsConsumer` → **`orders.flight`** (DEPARTED/LANDED → M4); M7 will also consume (delay/cancel → re-bag) |

### 🟡 `oneday.exceptions.events` — M11 (exceptions — not built)
| | |
|---|---|
| Event | `ExceptionsEvent` · Types `RTO_INITIATED, PICKUP_RESCHEDULED, DELIVERY_RESCHEDULED` |
| Producer | M11 (upcoming) · Consumer (dormant) orders `ExceptionsEventsConsumer` → **`orders.exceptions`** (RTO / reschedule → M4) |

---

### ⚪ `oneday.notifications.events` — notification service (not built)
Defined for OTP/SMS fan-out. No producer/consumer yet — which is why pickup/delivery OTP cleartext is
currently **logged/cached** for the demo instead of SMS'd to the customer.

---

## Not on the bus (important)

- **Van custody scans** — `VAN_LOAD`, `VAN_TO_DA`, `DA_TO_VAN`, `VAN_UNLOAD` are **not** RabbitMQ events.
  M6 records them to M8 through a **synchronous `ScanLedgerPort`** call (the van is a scan node). They
  will never appear on an exchange.
- **Pickup/Delivery OTP verify** — the door-OTP handshake is a **synchronous REST call** to M4
  (`/internal/v1/shipments/{ref}/pickup-otp|delivery-otp/verify`), not an event. It *triggers* an M4
  state change which then re-emits a `ShipmentStateChangedEvent`.

---

## Business scenario → code (end-to-end)

**Use case:** *Asha books a prepaid parcel from Bengaluru to a Delhi address. A pickup DA collects it,
it flies to Delhi, and a delivery DA hands it to the recipient against an OTP.*

Each step lists **business action → code → event on the wire**. Legs owned by unbuilt modules are
marked **(stubbed)**; the demo fakes them today.

| # | Business action | Code | Event |
|---|---|---|---|
| 1 | Asha books & pays | orders `BookingServiceImpl.book` persists `shipments` (BOOKED) → `applicationEventPublisher.publish(ShipmentBooked)` → `ShipmentEventProducer` (AFTER_COMMIT) | **`ShipmentCreatedEvent`** → `oneday.shipments.events` |
| 2 | Dispatch finds a pickup DA | dispatch `ShipmentEventsConsumer` (queue `m5.shipments`) → `DispatchService.assignPickup` → DA chosen (least-loaded, cron-feasible); `dispatch_queue` PICKUP row | — (writes DB; may emit `DaLifecycleEvent` PICKUP_ASSIGNED) |
| 3 | Sender gets a pickup code | dispatch emits `DaLifecycleEvent(PICKUP_ASSIGNED)`; orders `DaEventsConsumer` (queue `orders.da`) transitions M4 → `PICKUP_ASSIGNED` **and** `PickupOtpService.generate` | **`DaLifecycleEvent`** → `oneday.da.events` |
| 4 | DA collects; Asha reads OTP | DA app → REST `…/pickup-otp/verify` → M4 `PICKUP_ASSIGNED → PICKED_UP` | re-emits **`ShipmentStateChangedEvent`** |
| 5 | DA hands parcel to the van | dispatch emits `DaLifecycleEvent(PICKUP_COMPLETED)`; routing `DaFeedConsumer` (queue `routing.da`) binds the collect to a van loop | **`DaLifecycleEvent(PICKUP_COMPLETED)`** → `oneday.da.events` |
| 6 | Origin hub sorts → bags → flight **(stubbed: M7/M8/M9)** | would be `ScanEvent(HUB_ORIGIN_IN)`, `HubEvent(BAG_CREATED)`, `FlightEvent(DEPARTED/LANDED)` consumed by orders `ScanEventsConsumer`/`HubEventsConsumer`/`FlightEventsConsumer` driving M4 `AT_ORIGIN_HUB → … → AT_DEST_HUB`. In the demo, `DemoDaController.dispatchDrops` **fast-forwards** these M4 states directly. | (none today) → `scan/hub/flight.events` when built |
| 7 | Delhi hub sorts to the delivery stand | **M7 (stubbed)** would emit it; today `DemoDaController.dispatchDrops` emits it; routing `HubFeedConsumer` (queue `routing.hub`) → `VanManifestService.bindDelivery` binds the parcel to a drop-van loop | **`ParcelSortedForDeliveryEvent`** → `oneday.hub.events` |
| 8 | Recipient gets a delivery code | `dispatchDrops` → M4 `… → DROP_COLLECTED` + `DeliveryOtpService.generate` | **`ShipmentStateChangedEvent`** (DROP_COLLECTED) |
| 9 | Van runs hub→DA; nightly route plan | routing run emits `CronEvent(VAN_ARRIVED / VAN_RUNNING_LATE / ROUTE_PLAN_PUBLISHED)`; van custody scans go to M8 via the **synchronous `ScanLedgerPort`**, not the bus | **`CronEvent`** → `oneday.cron.events` |
| 10 | DA delivers; recipient gives OTP | DA app → REST `…/delivery-otp/verify` → M4 `DROP_COLLECTED → DROPPED` (Delivered) | re-emits **`ShipmentStateChangedEvent`** (DROPPED) |
| — | *If Asha cancels mid-flight* | orders `CancellationServiceImpl` → `ShipmentCancelledEvent`; dispatch `ShipmentEventsConsumer` drops the task (RTO if already picked up); M10 closes SLA | **`ShipmentCancelledEvent`** → `oneday.shipments.events` |

**What's genuinely flowing on the broker today:** steps 1–5, 7, 9 (and cancellation). Steps 6, 8, 10's
hub/flight legs are demo fast-forwards standing in for M7/M8/M9; the OTP verifies (4, 10) are REST, not
events.

---

## Operating the bus (health + inspection)

```bash
# every live consumer queue shows consumers=1; every *.dlq should be empty
rabbitmqadmin -N oneday list queues name consumers messages

# peek an exchange non-destructively while you trigger an action in the UI
rabbitmqadmin -N oneday declare queue name=demo.peek durable=false auto_delete=true
rabbitmqadmin -N oneday declare binding source=oneday.shipments.events destination=demo.peek routing_key='#'
rabbitmqadmin -N oneday get queue=demo.peek count=20 ackmode=reject_requeue_true
rabbitmqadmin -N oneday delete queue name=demo.peek
```

A non-empty `*.dlq` ⇒ a producer/consumer type-contract mismatch — check the `__TypeId__` header
against the consumer's mapped class.
