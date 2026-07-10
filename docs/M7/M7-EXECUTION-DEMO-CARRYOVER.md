# M7 Execution Demo — Carryover Ledger

Tracks work done on `demo/m7-execution` that must land on **`f-m7-design`** (real modules), split from
demo-only glue that stays on the demo branch. Maintained as the demo progresses.

**Legend:** 🟢 CARRY-OVER (real integration — port to `f-m7-design`) · 🔵 DEMO-ONLY (stays on demo branch)

---

## Phase 1 — pipeline seams (all 🟢 CARRY-OVER, all merged & tested here)

These are **real integration fixes**, not demo glue. Before them, every first-mile/last-mile hand-off
to M6 dead-lettered on a real broker (`oneday.da.events.dlq`=96, `oneday.hub.events.dlq`=32) because the
producing module emitted a `common` typed payload whose `__TypeId__` header didn't match the `routing`
record M6's `@RabbitListener` expected → `MessageConversionException` → DLQ → the parcel never bound.

**Root-cause pattern & the fix (applies bus-wide):** an exchange carries many payload shapes; a consumer
must accept a **common supertype** that every message on that exchange deserializes into (the `__TypeId__`
header resolves each to its concrete subtype, which is assignable to the supertype), then **discriminate
on the event-type discriminator carried in the message body** — never bind narrow routing keys, never
declare a concrete/foreign payload type as the listener parameter. This is how M5's
`ShipmentEventsConsumer(BaseShipmentEvent)` already worked; Phase 1 brings M6 and M7 in line. Queues stay
`#`-bound (fanout); each consumer decides what to act on. **No `RabbitStreamSupport` / topology change.**

| # | Change | Files |
|---|--------|-------|
| 1 | **Seam A (first mile).** M6 `DaFeedConsumer` consumes the shared `common.…DaLifecycleEvent`, discriminates on `eventType()`, binds a COLLECT loop only on `PICKUP_COMPLETED`. Maps `shipmentId → parcelId` (same identity across M4/M7); derives `validDate` from `occurredAt` in IST; `pickedUpAt = occurredAt`. Deleted the provisional `routing.events.payload.DaParcelPickedUpEvent`. | `routing/.../events/DaFeedConsumer.java`, deleted `.../payload/DaParcelPickedUpEvent.java`, javadoc on `common/.../events/DaLifecycleEvent.java` |
| 2 | **Seam B (last mile).** M6 `HubFeedConsumer` consumes the sealed base `common.…hub.HubEventPayload`, discriminates to `PARCEL_SORTED_FOR_DELIVERY`, casts to `common.…hub.ParcelSortedForDeliveryEvent` (its first six fields are the binder shape; the rest additive/ignored). Deleted the provisional `routing.events.payload.ParcelSortedForDeliveryEvent`; removed the now-obsolete cross-type Jackson test. | `routing/.../events/HubFeedConsumer.java`, deleted `.../payload/ParcelSortedForDeliveryEvent.java`, `routing/.../HubFeedToManifestWiringTest.java`, `hub/.../HubEventPayloadJacksonTest.java` |
| 3 | **M7 sort trigger live.** `ShipmentStateConsumer` now `autoStartup=true`; accepts base `BaseShipmentEvent` + discriminates (so CREATED/CANCELLED don't DLQ once enabled); on `AT_ORIGIN_HUB`/`AT_DEST_HUB` resolves `hubId = GridService.resolveCityId(originCity|destCity)` (**hub_id == city_id in v1**) and calls `HubReceivingService.receive(hubId, ref)` (which derives the direction from the parcel's M4 state). | `hub/.../events/ShipmentStateConsumer.java`, javadoc on `hub/.../events/HubMessagingTopology.java` |
| 4 | **Enable the dormant producer.** `dispatch.events.publish-da-events: true` so M5's `DaEventProducer` actually publishes to `oneday.da.events`. | `app/src/main/resources/application.yml` |

**Verified:** `mvn clean install` (app assembles) + routing (56) & hub (33) suites green. Stale DLQs
purged on CloudAMQP (`da`/`hub`/`cron` → 0). Existing durable queues are already `#`-bound as the code
expects — no broker rebinding needed.

### Known gaps to resolve on `f-m7-design` (surfaced by Phase 1)
- 🟢 **City-name resolution.** `resolveCityId` matches M4's uppercase city *name* → grid key
  case-insensitively, but **"BENGALURU" ≠ grid key "bangalore"**. Fine for a Delhi↔Mumbai demo; a real
  gap for Bangalore. Needs a canonical city-code map (or align M4 origin/dest city with grid keys).
- 🟢 **`orders.hub` tolerant reader.** M4's `HubEventsConsumer` declares the parameter `HubEvent` (a
  reader type *outside* the sealed `HubEventPayload` hierarchy). Over a real broker the `__TypeId__`
  header deserializes each message to the concrete `HubEventPayload` subtype, which is **not** assignable
  to `HubEvent` → likely DLQ once M7 produces. Apply the same base-supertype+discriminate fix (consume
  `HubEventPayload`, switch on `eventType()`) — mirrors the Seam B fix. Verify during the live run.
- 🟠 **Origin-hub arrival trigger (open design).** Phase 1 wires the M4-state path (`AT_ORIGIN_HUB` →
  receive). The alternative — the M6 `VAN_UNLOAD` custody scan at the hub driving M7 receive — remains a
  real M6↔M7 seam decision. Whichever wins, M4 must actually transition the parcel to `AT_ORIGIN_HUB`
  (nobody produces that today outside the demo driver).

---

## Phase 2 — orchestration driver

The driver (`app/.../demo/DemoJourneyService` + `DemoJourneyController` + `DemoLog`) is **🔵 DEMO-ONLY**
(`@Profile("!prod")`, under `/api/demo/journey/**` which `DemoSecurityConfig` already opens). It calls
module public services **in-process** (no HTTP/auth) and drives the *real* pipeline over the broker.

**Landed (compiles + app assembles):**
- Scaffolding: background-thread single run, `RunStatus` rollup, per-parcel `JourneyRecord` (pipeline-strip
  token), `DemoLog` feed; endpoints `run-day` / `stop` / `run-status` / `journeys` / `run-events`.
- Stage 1 **Book** — N B2C shipments via `BookingService.bookSettled` (PREPAID, no gateway) from live-grid
  vertex coordinates (`GridService.getVertices`) so serviceability resolves; each emits `ShipmentCreatedEvent`.
- Stage 2 **Assign** — polls `DispatchQueueRepository.findActiveByShipmentIdAndTaskType(…PICKUP)` to read
  M5's async assignment (DA + task id) back per parcel.
- Stage 3 **DA pickup** — per parcel: waits for M4 to apply `PICKUP_ASSIGNED` (async, post-`main` merge),
  `markEnRoute` → `PickupOtpService.generate` (known cleartext) → `verifyOtp` (→ PICKED_UP, emits
  `PICKUP_COMPLETED` = Seam A) → `recordVanHandoff` (emits `VAN_HANDOFF_COMPLETED`). In-process service calls.

- Stage 4 **Collect van** — `DemoVanDriver.waitForBinds` (Seam-A COLLECT binds settle) then `driveLoops`
  animates the origin-city collect loop (GPS + custody `VAN_UNLOAD` at the hub).
- Stage 5–6 **Origin hub + freight** — `advanceTo(AT_ORIGIN_HUB)` triggers M7's outbound sort/flight bag;
  a compressed pause then `advanceTo(AT_DEST_HUB)` walks the M9 air-leg states (nobody produces them yet).
- Stage 7–8 **Dest hub + deliver** — `AT_DEST_HUB` triggers M7's inbound sort → `PARCEL_SORTED_FOR_DELIVERY`
  (Seam B); `waitForBinds` + `driveLoops` animate the dest-city deliver loop; `advanceTo(DROPPED)` completes.

**🔵 DEMO-ONLY — `routing.demo.DemoVanDriver`** (`@Profile("!prod")`): the van telemetry/custody replay,
ported from the M6 execution demo's `DemoExecutionService` drive-half so routing internals stay in routing;
`app`'s `DemoJourneyService` calls it for Stages 4 and 7–8.

**Prerequisites the driver assumes (setup, not carryover):** both the origin and dest cities need an
APPROVED route plan for *today* (Seam-A/B binds + the van replay key off it) and seeded grids. The last
mile walks state to `DROPPED` directly (no auto DELIVERY task in v1, Q-M4-2) — a real M5 delivery-assign +
`DeliveryOtpService` verify is the eventual replacement.

**⚠️ Needs live validation (compile-only so far):** the async choreography — M4 applying `PICKUP_ASSIGNED`,
Seam-A/B bind timing, M7 sort resolving, and whether M4's `orders.hub` consumer (the flagged `HubEvent`
DLQ risk) races the driver's `advanceTo` walk — can only be confirmed by running the app against the DB +
CloudAMQP. `advanceTo` is written to tolerate concurrent advances, but the interplay is unproven.

**✅ RESOLVED on `main` (merged in) — the `PICKUP_ASSIGNED` gap.** `main` (commit `49b73d9`, "extract real
backend work from the m6-execution demo branch") added `DaEventProducer.emitPickupAssigned` and calls it in
`DispatchServiceImpl.assignPickup`, so M5 now drives `BOOKED→PICKUP_ASSIGNED` + OTP mint for real (no demo
workaround needed). The merge also enriched `common.DaLifecycleEvent` with explicit `parcelId` + `validDate`
(populated for parcel-scoped events; `parcelId == shipmentId` in v1) — this **is** the Seam-A contract, and
`DaFeedConsumer` now reads those fields directly. Also pulled in: a **delivery-OTP flow** (`DeliveryOtpService`,
`DeliveryOtpController`, Flyway `V4_21`) for Stage 8, and new common ports `DaCronSchedulePort` /
`DaPickupQueuePort`. **Net effect on this ledger:** the Phase-1 seam fixes (A/B, M7 trigger) remain the only
routing/hub carryover; the M5-side first-mile choreography is now real on `main`.

**🟢 CARRY-OVER candidates surfaced by the driver (confirm during live run):**
- Demo books with a placeholder pincode and relies on **coordinate-primary serviceability**; if pincode
  ever gates, M4 needs city-consistent pincodes. (Cosmetic today.)
- M4 `originCity`/`destCity` must resolve in **both** pricing (name→IATA via `CityCodes`) **and** grid
  (`resolveCityId`, name→key). The Bengaluru/"bangalore" mismatch (Phase-1 gap) bites here too — demo
  defaults to a Delhi↔Mumbai pair. A single canonical city catalog would remove the double-mapping.
