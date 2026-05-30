# M5 — Dependency Questions for M1, M3, M4 Implementers

These are blockers or interface ambiguities that M5 must resolve before implementation begins. Each section is addressed to the team responsible for that module.

---

## Questions for M1 (Auth) Team

---

### Q-M1-1 — DA JWT role name

**Context:** M5 has DA-facing REST endpoints (`POST /dispatch/da/{id}/gps`, `/tasks/{id}/verify-otp`, etc.) that require JWT authentication with a DA role.

**Question:** What is the exact Spring Security role name assigned to a Delivery Associate? Is it `ROLE_DELIVERY_ASSOCIATE`, `DELIVERY_ASSOCIATE`, or something else defined in the M1 role enum?

**Why it matters:** M5's `@PreAuthorize` annotations and Spring Security config will reference this name directly. Getting it wrong means all DA endpoints fail with 403.

---

### Q-M1-2 — City claim in DA JWT

**Context:** M5 assigns DAs to tiles within their city. When a DA's GPS heartbeat or task action arrives, M5 may need to verify that the DA belongs to the city the tile is in.

**Question:** Does the DA JWT carry a `city_id` claim? If yes, what is the claim key name (e.g., `cityId`, `city_id`, `city`)? If not, how should M5 verify a DA is operating in the correct city — should it always query M3 for the DA's current tile assignment?

---

### Q-M1-3 — Station manager JWT city scope

**Context:** M5 exposes `GET /dispatch/tiles/{tile_id}/queue` for station managers. The station manager should only be able to view tiles in their city.

**Question:** Does the Station Manager JWT include a `city_id` claim that M5 should enforce at the endpoint level? What is the claim key name? Or is city-scoping handled at a shared middleware layer (e.g., a filter that M5 simply inherits)?

---

### Q-M1-4 — Internal service token mechanism

**Context:** M5 calls two M4 internal endpoints:
- `POST /internal/v1/shipments/{ref}/pickup-otp/verify`
- `POST /internal/v1/shipments/{ref}/pickup-otp/resend`

M4's design doc specifies **Auth: Internal service token** for these endpoints.

**Question:** What exactly is this "internal service token"?
- Option A: A static shared secret stored in `application.yml`, sent as `Authorization: Bearer <static-token>`
- Option B: A service-level JWT issued by M1 for M5 as a service principal
- Option C: mTLS — mutual TLS between services

If Option B: does M1 expose an endpoint to issue service-level tokens? What is the issuing flow? How long is the token TTL and how does M5 refresh it?

**Why it matters:** M5 makes OTP verify calls in the synchronous hot-path of every DA pickup. If the token is short-lived and M5 must refresh it, the refresh mechanism must not add latency to the OTP verify call.

---

### Q-M1-5 — Admin role name for internal endpoints

**Context:** M5 has a station manager view endpoint. In some cases, admin users (who can manage all cities) also need access.

**Question:** What is the exact Spring Security role name for ADMIN? Is it `ROLE_ADMIN`? Should M5's `@PreAuthorize` be `hasAnyRole('STATION_MANAGER', 'ADMIN')` or is there a permission-based check?

---

## Questions for M3 (Grid) Team

---

### Q-M3-1 — REST controllers: implemented or pending?

**Context:** The M3 domain model and DTOs are present in the codebase (`TileAtResponse`, `DaAssignmentResponse`, `TileLoadScoreResponse`, `AssignmentResponse`). However, there are no controller `.java` files visible in the grid module's source tree.

**Question:** Are the following REST endpoints already implemented, or are they still planned?
- `GET /grid/tile-at?city_id={}&lat={}&lon={}` → `TileAtResponse`
- `GET /grid/assignments?city_id={}&date={}` → list of `DaAssignmentResponse`
- `GET /grid/tiles/{tile_id}/load-score` → `TileLoadScoreResponse`

M5 needs these at shift start and in the real-time assignment hot-path. If not yet implemented, what is the expected delivery timeline?

---

### Q-M3-2 — `nDasOnTile` in the assignments endpoint response

**Context:** `AssignmentResponse` includes `nDasOnTile`. However `DaAssignmentResponse` (the per-DA view used by `GET /grid/assignments`) does **not** include it — it only shows `tileIds`.

M5 needs to know, for each tile in a DA's territory, whether `nDasOnTile > 1` (i.e., the tile is shared with another DA). This determines whether M5 uses round-robin dispatch within the tile or assigns all orders to one DA.

**Question:** Should M5 call `GET /grid/assignments?city_id={}&date={}` (which returns `DaAssignmentResponse` without `nDasOnTile`) and then cross-reference with `AssignmentResponse` for each tile? Or will M3 update the `GET /grid/assignments` response to include `nDasOnTile` per tile?

**Suggested fix:** Add `Map<UUID, Integer> nDasPerTile` to `DaAssignmentResponse` (tile_id → count).

---

### Q-M3-3 — `TileLoadScoreResponse` fields vs design doc

**Context:** The `TileLoadScoreResponse` record has three fields: `unservedOrders`, `adjustedLoadScore`, `severity`. The M3 design doc (§7.8) describes a richer response: `expected_orders`, `shift_elapsed_pct`, `load_score` (raw), and `adjusted_load_score`.

M5's cross-territory eligibility check (§10.2) uses `adjusted_load_score >= 1.5` to decide whether to search for an adjacent DA. The current record is sufficient for that check.

**Question:** Is the current 3-field `TileLoadScoreResponse` the final shape, or will the additional fields (`expectedOrders`, `loadScore`, `shiftElapsedPct`) be added? M5 will implement against what is available, but confirming the final shape avoids a later refactor.

---

### Q-M3-4 — `dispatch.tile_queue_depth` Kafka topic consumer

**Context:** M5 publishes a snapshot every 5 minutes to topic `oneday.dispatch.tile_queue_depth` (to be added to `KafkaTopics.java`). The M3 design doc (§16.2) describes `TileQueueDepthConsumer` in M3's `events/` package that consumes this topic and updates M3's in-memory load scores.

**Question:** Has M3 implemented `TileQueueDepthConsumer`? If not, what is the dependency: does M3 need this consumer before `GET /grid/tiles/{tile_id}/load-score` can return meaningful data, or is there a temporary fallback (e.g., return zero / default)?

**Related:** The `TileLoadScoreResponse.severity` field — does M3 calculate this in the same `IntradayMonitorJob` using the thresholds in `GridProperties.intraday`? Specifically: `WARNING` when `adjustedLoadScore >= 1.5` sustained 15 min, `CRITICAL` when `>= 2.0` sustained 10 min?

---

### Q-M3-5 — `service_time_min` at shift start

**Context:** M5 reads per-tile `service_time_min` from `TileDemandSnapshot` at shift start to use in cron feasibility calculations. `TileDemandSnapshot` has `serviceTimeMin` (confirmed in the entity). The field is the average minutes spent at the customer's door per pickup.

**Question:** Does M3 expose an API endpoint to read today's `TileDemandSnapshot` for a city, or should M5 query the `tile_demand_snapshot` table directly (since M3 and M5 are in the same monolith)? If there's a planned endpoint, what is the path and response shape?

If direct DB read is preferred: which Spring Data repository should M5 wire? `TileDemandSnapshotRepository` is in the `grid` module — will M3 mark it as a shared/public bean or expose a `GridServicePort` method instead?

---

### Q-M3-6 — Intraday assignment change notification to M5

**Context:** M5 loads the DA-to-tile map at shift start into memory. If M3 applies an intraday override (e.g., a station manager moves a tile from DA A to DA B), M5's in-memory map becomes stale.

**Question:** Does M3 publish any Kafka event when an intraday override (`INTRADAY_OVERRIDE` or `INTRADAY_SHARE`) is approved? If yes, on which topic and with what payload? M5 needs to consume this event to update its in-memory assignment map without a restart.

If no event is planned: should M5 poll M3's `GET /grid/assignments` periodically (e.g., every 5 minutes during shift), or is there a push mechanism?

---

### Q-M3-7 — `tile-at` response when GPS is outside grid bounds

**Context:** DA GPS positions can occasionally be outside the city grid bounding box (e.g., on the city boundary, on a motorway en-route to a far pickup). M5 calls `GET /grid/tile-at` to resolve GPS coordinates to a tile.

**Question:** What does the `tile-at` endpoint return when the coordinates are outside the grid? Does it return `active: false` with a best-guess tile, return `null`, or return a 404? M5 needs to handle this gracefully (fall back to using the DA's last known valid tile for feasibility calculations).

---

## Questions for M4 (Orders) Team

---

### Q-M4-1 — `ShipmentCreatedEvent` has `originTileId` — does M5 use it directly?

**Context:** `ShipmentCreatedEvent` (from `common`) already carries `originTileId` (UUID). M5 was going to call M3's `tile-at` API to resolve the pickup address to a tile, but `originTileId` is already embedded in the event.

**Question:** Is `originTileId` always populated in `ShipmentCreatedEvent`? Can M5 use it directly to skip the M3 tile-at call, or is there a case (e.g., M3 tile-at failed at booking time) where it can be null?

If `originTileId` can be null: should M5 fall back to calling M3's tile-at API, or should M5 reject the assignment and wait for M4 to retry the shipment?

---

### Q-M4-2 — `ShipmentStateChangedEvent` for `HANDED_TO_DROP_VAN`

**Context:** M5 consumes state change events to trigger delivery assignment when a shipment reaches `HANDED_TO_DROP_VAN`. M5 needs the delivery address coordinates, `dropType`, and `destCity` to assign the correct DA.

**Question:** Does M4's `ShipmentStateChangedEvent` include the delivery address (`destLat`, `destLon`, `destPincode`, `destCity`) and `dropType` in its payload? Or does M5 need to call M4's internal `GET /internal/v1/shipments/{id}` endpoint to fetch these fields?

Including coordinates in the event would be strongly preferred — a separate GET call adds latency and a potential failure point in the delivery assignment flow.

---

### Q-M4-3 — OTP verify endpoint: `{ref}` or `{id}`?

**Context:** M4's design doc defines the OTP verify endpoint as `POST /internal/v1/shipments/{ref}/pickup-otp/verify` using the human-readable reference (e.g., `1DD-BLR-20260509-00042`). M5's `ShipmentCreatedEvent` carries both `shipmentId` (UUID) and `shipmentRef` (string).

**Question:** Does the OTP verify endpoint accept `{ref}` (the human-readable reference) or `{id}` (the UUID)? The design shows `{ref}`, but UUID lookups are simpler and more robust (no string parsing). Confirming which one M5 should use avoids a silent mismatch.

---

### Q-M4-4 — OTP resend response: remaining count included?

**Context:** The OTP verify endpoint design says max 3 resends. M5's DA app surfaces "resends remaining" to the DA so they know before the last attempt.

**Question:** Does `POST /internal/v1/shipments/{ref}/pickup-otp/resend` return the remaining resend count in the response body? If yes, what is the response shape? If the max (3) is exceeded, what HTTP status and error code does M4 return? (M5's design assumes `422 MAX_RETRIES_EXCEEDED` — confirming this.)

---

### Q-M4-5 — OD-8: delivery verification mechanism resolved?

**Context:** OD-8 in M4's design is open: delivery verification at `DROP_COLLECTED → DROPPED` is either OTP (mirrors pickup) or QR scan. M5's implementation depends on which mechanism is chosen.

**Question:** Is OD-8 resolved? Specifically:
- If **OTP**: M5 must implement the same OTP-verify flow as pickup (DA app prompts receiver for OTP; M5 calls M4's verify endpoint; M4 transitions `DROP_COLLECTED → DROPPED`).
- If **QR scan**: M5 simply calls `POST /dispatch/da/{id}/tasks/{id}/drop-completed` and M4 transitions on `DROP_COMPLETED` event. No internal HTTP call to M4 needed.

This is the highest-priority open item for M5's delivery flow. The two paths require different API endpoints on the DA-facing side.

---

### Q-M4-6 — Minimum required fields in M5's DA events

**Context:** M5 publishes events on `oneday.da.events` that M4 consumes to drive state transitions. `DaEventBase` (to be added to `common`) has: `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `shipmentId`, `shipmentRef`, `daId`, `cityId`.

**Question:** For each M4-consumed `DaEventType`, what additional fields does M4 need beyond `DaEventBase`? Specifically:

| DaEventType | Does M4 need anything beyond DaEventBase? |
|---|---|
| `PICKUP_ASSIGNED` | `pickupEta`, `queuePosition`? Or just `shipmentId`? |
| `PICKUP_FAILED` | `reasonCode` (string)? Which reason codes are valid? |
| `VAN_HANDOFF_COMPLETED` | `vanId`, `parcelScan`, `parcelCount`? |
| `DROP_ASSIGNED` | `deliveryEta`? |
| `DROP_COLLECTED` | `parcelScan`? |
| `DROP_COMPLETED` | Nothing extra? |
| `DROP_FAILED` | `reasonCode`? Which codes are valid? |

M5 needs this list to finalise the event payload classes that will go into `common`. Anything M4 does not need should be stripped to keep the contracts minimal.

---

### Q-M4-7 — Does M4 emit `ShipmentStateChangedEvent` for every state transition?

**Context:** M5 consumes `ShipmentStateChangedEvent` to detect `HANDED_TO_DROP_VAN`. But M4's design (KDD-3) says: "M4 does not persist raw Kafka event payloads... only the resulting state transition is stored."

**Question:** Does M4 emit a `ShipmentStateChangedEvent` on `oneday.shipments.events` for **every** state transition? Or only for a selected subset? Specifically, M5 needs to confirm that `HANDED_TO_DROP_VAN` always produces an event on the Kafka topic.

If not every transition produces a Kafka event: what is the complete list of transitions that do? (M5 may also need `PICKUP_ASSIGNED` confirmation — though M5 itself produces that transition, M5 is not also a consumer for it.)

---

### Q-M4-8 — `CANCELLED` event: does it carry the current state of the shipment?

**Context:** When a shipment is cancelled, M5 needs to remove the task from the DA's queue (if QUEUED) or handle it (if IN_PROGRESS). M5 needs to know whether the cancellation was before or after `PICKUP_ASSIGNED`.

**Question:** Does `ShipmentCancelledEvent` include the shipment's state at the time of cancellation? If the state is `PICKUP_ASSIGNED` or later when cancelled, M5 needs to notify the DA app (the DA is en-route). If the state is just `BOOKED`, no DA is involved yet.

What is the `ShipmentCancelledEvent` payload shape? The current `common` module has only `ShipmentCancelledEvent extends BaseShipmentEvent` with no additional fields visible.

---

### Q-M4-9 — `cron.scheduled` event: which topic and who produces it?

**Context:** M5's design doc (§12.1) states that M6 populates `da_cron_assignment` by producing a `cron.scheduled` event that M5 consumes at nightly replan. However, `CronEventType` in `common` currently defines only `DEPARTED_HUB` and `DEPARTED_AIRPORT` — both runtime departure events, not the nightly schedule notification.

**Question (joint for M4/M6):** What event (topic + payload) does M6 emit after completing the nightly replan that tells M5 "DA X should meet van Y at vertex V at time T tomorrow"? Does this go on `oneday.cron.events`, and is a new `CronEventType.SCHEDULE_PUBLISHED` value needed? Or does M6 write directly to a shared DB table that M5 reads at shift start?

---

*Last updated: 2026-05-19 — covers M5 design doc v1.1.*
