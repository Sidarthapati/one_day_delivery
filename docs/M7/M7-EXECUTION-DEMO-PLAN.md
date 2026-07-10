# M7 Execution Demo — Implementation Plan (end-to-end first-mile + last-mile)

| Field | Value |
|-------|-------|
| **Demo branch** | `demo/m7-execution` — cut from **`f-m7-design`** (full real modules: M4, M5, M6, M7 all built) |
| **Reference branch** | `demo/m6-execution` — the existing "run the day" driver (`DemoExecutionService`) + `demo-ui` (Planning/Execution toggle, live van map). We **reuse and build on** its RabbitMQ-feed / telemetry / custody / map machinery; we **replace** its synthetic feed with the real pipeline. |
| **Goal** | One demo that shows a parcel's **whole journey** over the real RabbitMQ bus: order → M5 assigns a DA → DA picks up → hands to van → van to origin hub → hub sort + flight bag → **freight (flight)** → destination hub → sort by **territory / whichever-loop-first** → van last-mile → DA delivers. Visualized properly, end to end. |
| **Shape** | **Exactly 3 phases.** Ph1 = make the real chain actually flow (backend seams). Ph2 = the demo orchestration driver (drive a compressed virtual day end-to-end). Ph3 = the visualization (extend `demo-ui` to show the full journey). |

---

## 0. The end-to-end flow (what the demo must show)

Every arrow is a **real RabbitMQ message** between real modules unless marked. This is the flow you described.

```
FIRST MILE
──────────
 [M4] customer books ──► ShipmentCreatedEvent ═══► oneday.shipments.events
                                                     │
 [M5] ShipmentEventsConsumer ◄───────────────────────┘   reads the order
      dispatchService.assignPickup → picks a DA (cron-feasibility hard constraint)
      → creates a PICKUP task on the DA's queue
                                                     │
 [DA app]  /dispatch/da/{da}/tasks/{task}/en-route → picks up from customer (OTP)
           → PICKUP_COMPLETED ═══► oneday.da.events
           /van-handoff  (DA meets the cron van, scans parcels into it)
           → VAN_HANDOFF_COMPLETED ═══► oneday.da.events
                                                     │
 [M6] DaFeedConsumer ◄───────────────────────────────┘   binds the parcel to the
      van's COLLECT loop  → van drives the collect circuit → brings parcels to the hub
      → VAN_UNLOAD custody scan at the origin hub
                                                     │
 [M7] origin hub: receive (AT_ORIGIN_HUB) → SortService.resolveOutbound
      → FlightAssignmentPort (STUB: assigns flight + cutoff)  ═══ FREIGHT EXCHANGE ═══
      → FlightBag on a Stand → seal at cutoff → manifest → DISPATCHED_TO_AIRPORT
      (emits STAND_ASSIGNED / BAG_CREATED / BAG_SEALED / MANIFEST_GENERATED)

  ✈  flight departs → lands (+2h, compressed in the demo)

LAST MILE
─────────
 [M7] destination hub: break-bag (AT_DEST_HUB) → SortService.resolveInbound
      hex ─► TerritoryPort (M3) ─► DeliveryRoutePort (M6)
      → pick the bag: ROUTE bag if a van route exists, else DA-TERRITORY bag
        ("based on the territory it assigns it to, or whichever comes first")
      → DeliveryBag on a Stand → PARCEL_SORTED_FOR_DELIVERY ═══► oneday.hub.events
                                                     │
 [M6] HubFeedConsumer ◄───────────────────────────────┘   binds the parcel to a
      DELIVER loop → van loads the delivery bag → drives the delivery circuit
                                                     │
 [DA app]  /drop-completed (+ OTP, + COD) → DROP_COMPLETED ═══► oneday.da.events → done
```

**The two hand-offs to M6 are the crux — and both are currently broken over a real broker (§1).** The rest already works: M5 assigns for real, the DA endpoints exist, M6 drives vans/telemetry/custody, M7 sorts. The demo's job is to make the chain *connect* and *show it*.

---

## 1. The two integration seams to fix (both are live DLQ evidence, both the same class)

Proven by `rabbitmqadmin` against the live broker: `oneday.da.events.dlq` = 96, `oneday.hub.events.dlq` = 32, all `reason=rejected`. Cause: the producing module emits a `common` typed payload whose `__TypeId__` header doesn't match the `routing` record M6's `@RabbitListener` expects → `MessageConversionException` → DLQ → **the parcel never binds**.

### Seam A — first mile: M5 `DaLifecycleEvent` → M6 collect bind
- M5 `DaEventProducer` emits **`common.kafka.events.DaLifecycleEvent`** (shipment-level: `shipmentId`, `shipmentRef`, `daId`, `cityId`, `eventType`) on `oneday.da.events`. Gated by `dispatch.events.publish-da-events` (**default false**).
- M6 `DaFeedConsumer` expects **`routing.events.payload.DaParcelPickedUpEvent`** (`parcelId`, `cityId`, `daId`, `validDate`, `pickedUpAt`). The `DaLifecycleEvent` javadoc explicitly notes it "does **not** satisfy that shape" (no `parcelId`).
- **Fix:** M6 consumes `DaLifecycleEvent` directly; filter to the **bind trigger event type** and map `shipmentId → parcelId` (they are the same identity — `parcelId ≡ shipmentId` across M4/M7). **Contract decision to confirm:** bind COLLECT on **`PICKUP_COMPLETED`** (parcel is in DA custody, ready for the van) vs **`VAN_HANDOFF_COMPLETED`** (physically in the van). Recommend `PICKUP_COMPLETED` for the loop bind; `VAN_HANDOFF_COMPLETED` then drives the custody/telemetry. Enable the producer (`publish-da-events=true`).

### Seam B — last mile: M7 `ParcelSortedForDeliveryEvent` → M6 deliver bind
- M7 emits `common.kafka.events.hub.ParcelSortedForDeliveryEvent` (11 fields); M6 `HubFeedConsumer` expects `routing.events.payload.ParcelSortedForDeliveryEvent` (6 fields). The DLQ'd ones even carry a **stale** `__TypeId__` (old `common.kafka.events.ParcelSortedForDeliveryEvent`, since moved to `…events.hub`).
- **Fix (recommended):** M6 consumes the shared `common.…hub.ParcelSortedForDeliveryEvent` (delete the provisional `routing` duplicate — its own comment says "finalize with M7 owner"). `__TypeId__` then matches by construction.

### Also (both seams)
- **Enable the dormant ends:** `dispatch.events.publish-da-events=true`; M7 `ShipmentStateConsumer` `autoStartup=true` + complete its `TODO(resolve hub UUID)` (a `cityCode → hubId` resolver — M7 seeds one hub per `grid.cities` UUID).
- **Tighten routing keys:** the feed queues (`routing.hub`, `routing.da`, `orders.*`) bind `#`, so every event type hits every consumer and the wrong ones reject → DLQ noise. Bind the specific routing keys M6 needs (`PICKUP_COMPLETED`, `PARCEL_SORTED_FOR_DELIVERY`) so the bus is clean. This closes the long-standing "tighten to routing keys before M5/M7 live" item — they're live now.

> These fixes are **real integration**, not demo glue — flag them as CARRY-OVER to `f-m7-design` in Phase 3's ledger.

---

## PHASE 1 — Make the real chain flow (backend, over the real broker)

**Goal:** without any UI, a single order walks the entire §0 pipeline over CloudAMQP with real M4/M5/M6/M7, nothing DLQ'd.

1. **Fix Seam A** (M6 consumes `DaLifecycleEvent`, maps `shipmentId→parcelId`, binds COLLECT on `PICKUP_COMPLETED`); enable `publish-da-events`.
2. **Fix Seam B** (M6 consumes the `common` hub payload).
3. **Enable M7's `ShipmentStateConsumer`** + `cityCode→hubId` resolver, so `AT_ORIGIN_HUB` / `AT_DEST_HUB` states actually trigger receive/sort (today REST/test-driven only).
4. **Origin-hub arrival from the van:** decide the trigger for M7 receive — the M6 `VAN_UNLOAD` custody scan at the hub, or the M4 state `AT_ORIGIN_HUB`. Wire whichever M4/M6 actually produces; the van→hub custody hand-off is the M6↔M7 seam (M6 originates the scan, M7 receives + reconciles).
5. **Tighten feed-queue routing keys**; purge the stale DLQs once (`rabbitmqadmin … purge queue name=oneday.{hub,da}.events.dlq`).
6. **Prove it** with a real-broker integration test (Testcontainers RabbitMQ or CloudAMQP): book 1 shipment → assert M5 creates a pickup task → simulate DA `PICKUP_COMPLETED` → assert M6 COLLECT bind → simulate van→hub + `AT_ORIGIN_HUB` → assert M7 flight bag → simulate `AT_DEST_HUB` → assert `PARCEL_SORTED_FOR_DELIVERY` → assert M6 DELIVER bind. This single test is the backbone; Phase 2 automates the same steps for many parcels with a clock.

**Exit:** `mvn clean install` green; one parcel provably traverses M4→M5→M6→M7→M6 over the broker; `da/hub.events.dlq` stay at 0.

---

## PHASE 2 — The demo orchestration driver (drive a compressed virtual day, end to end)

**Goal:** one endpoint runs the whole §0 flow for N parcels over the real broker, time-compressed, emitting a unified per-parcel state stream the UI polls. Build on `demo/m6-execution`'s `DemoExecutionService` shape (single run, background tick, in-memory `DemoLog`, `RunStatus`), but **drive the real modules** instead of faking the two feeds.

New: `app` (or `hub`) `…/demo/DemoJourneyService.java` (`@Profile("!prod")`) — a cross-module orchestrator (in `app` so it can touch M4/M5/M6/M7 public services), + `DemoJourneyController` (`/api/demo/journey/run-day|run-status|run-events`), + `DemoSecurityConfig` opening `/dispatch/**`, `/hub/**`, `/routing/**`, `/api/demo/**`.

The run (each step a real call / real message, drawn from the live grid so binds resolve):
1. **Book N real M4 shipments** for the demo city-pair (via `BookingService`, PREPAID mock gateway) → `ShipmentCreatedEvent`.
2. **M5 assigns** (real, via the consumer) → pickup tasks appear; the driver reads them back.
3. **Drive the DA app** per task over the real endpoints: `/en-route` → OTP pickup → `PICKUP_COMPLETED` → `/van-handoff` → `VAN_HANDOFF_COMPLETED`. (This exercises M5 for real; no faked DA events.)
4. **M6 binds COLLECT** (Seam A) → drive the collect van via telemetry/custody (reuse `VanTrackingService`/`CustodyService` from the M6 demo) → `VAN_UNLOAD` at the hub.
5. **Origin hub** (M7): parcels receive → sort → flight bags fill; the driver advances a **compressed clock** to each bag's flight-stub cutoff → seal → dispatch.
6. **Freight:** wait out the compressed flight time → publish `AT_DEST_HUB`.
7. **Destination hub** (M7): break-bag → inbound sort → delivery bags (route or DA-territory) → `PARCEL_SORTED_FOR_DELIVERY`.
8. **M6 binds DELIVER** (Seam B) → drive the delivery van → DA `/drop-completed` (+COD) → `DROP_COMPLETED`. Done.

Emit a **per-parcel journey record** (`{ref, stage, daId, vanId, flightNo, standNo, city}`) updated at each hop, plus the raw event feed — this is what Phase 3 renders. Reuse the M6 demo's `resetDay` and "run for today" constraint (telemetry resolves manifests by today).

**Exit:** one POST drives ~20 parcels start→finish; `run-status` shows every parcel's live stage; no DLQ.

---

## PHASE 3 — Visualization (extend `demo-ui`, show the whole journey)

**Goal:** watch the §0 flow happen. Add a **Journey** view alongside the reused Planning/Execution tabs.

Reuse from `demo/m6-execution`: `ExecutionMap` (moving, lateness-coloured van markers), `HexMap`/territory layers, the RabbitMQ feed panel, `routingApi`, the run controls + polling.

Build (new `demo-ui/src/components/journey/`):
1. **Journey pipeline strip** — the horizontal §0 stages (Booked → DA assigned → Picked up → In van → **Origin hub** → **Flight** → **Dest hub** → Sorted → In delivery van → **Delivered**); each parcel is a token that advances stage by stage, live from `run-status`. This is the single "see the whole flow" artifact.
2. **Hub board** (the M7-specific ask) — parcels currently in each hub + their state; **stands** as shelves holding flight bags / delivery bags (count, weight, sealed?); the **flight-stub schedule** (4 daily departures, cutoff = departure − lead, +2h arrival) with the next cutoff highlighted; served by a `GET /hub/{hubId}/flight-schedule` read + existing `/bags` + `/delivery-bags`.
3. **Map** — first mile (DA pickup pins → collect van → origin hub) and last mile (dest hub → delivery bags → deliver van → drop pins), reusing `ExecutionMap`. Origin and destination cities shown (two mini-maps or a toggle).
4. **Live event feed** — real `oneday.{shipments,da,hub}.events` types streaming (`ShipmentCreated`, `PICKUP_COMPLETED`, `VAN_HANDOFF_COMPLETED`, `STAND_ASSIGNED`, `BAG_SEALED`, `PARCEL_SORTED_FOR_DELIVERY`, `DROP_COMPLETED`) — the "real RabbitMQ translations" made visible.
5. **Carryover ledger** `docs/M7/M7-EXECUTION-DEMO-CARRYOVER.md` — split CARRY-OVER (Seam A/B fixes, `ShipmentStateConsumer` resolver, routing-key tightening — all real, must land on `f-m7-design`) vs DEMO-ONLY (`DemoJourneyService`, the Journey UI).

**Exit:** a viewer follows a booked parcel across the pipeline strip and the map — DA pickup, van to hub, sorted onto a flight bag against the schedule, flown, sorted at the destination by territory/first-loop, loaded onto a delivery van, delivered — with the real event feed alongside.

---

## Ground rules (from the M6 demos — don't relearn)
- **`rabbitmqadmin -N cloudamqp`** is the source of truth for what's really on the bus (installed on PATH; auth via `CLOUDAMQP_URL`, **not** `CLOUDAMQP_APIKEY`). Live queues sit at 0 (app drains them) — diagnose in `*.dlq`.
- `MessagingConfig` trusted-packages must be `"*"` (already true here) or every consumer DLQs.
- Keep `hibernate.jdbc.batch_size` in `application.yml` (env vars can't set map keys); rebuild+restart for any config/Java change.
- M6 reads **APPROVED** route plans (no ACTIVE step); the destination sort's `DeliveryRoutePort` needs an APPROVED plan for the day. Run **for today** (telemetry resolves by today).
- Never commit CloudAMQP / Razorpay / DB creds — all via env.

*Plan v0.2 — rewritten for the full first-mile + last-mile journey over real M4/M5/M6/M7. Ph1 fixes the two M6 hand-off seams (first-mile DA-event, last-mile sorted-for-delivery) and enables the dormant producers/consumers so the chain flows; Ph2 is the cross-module run-day driver that walks a compressed virtual day end to end; Ph3 visualizes the whole journey by extending the existing `demo-ui`.*
