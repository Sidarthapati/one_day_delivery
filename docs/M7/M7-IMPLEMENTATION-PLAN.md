# M7 — Implementation Plan

| Field | Value |
|-------|-------|
| **Module** | `hub` (`com.oneday.hub`), artifactId `hub` — depends on `common`, `barcode` (M8 iface), `orders` (M4 iface) |
| **Design doc** | [`docs/M7/M7-HUB-DESIGN.md`](./M7-HUB-DESIGN.md) (v0.1) — **read §0 first**, then this plan implements it |
| **Status** | Not started — `hub/` is an empty skeleton (pom only) |
| **Shape** | **3 PRs.** PR1 = origin hub end-to-end (the spine). PR2 = destination hub + the M6 seam + same-city. PR3 = resilience/ops (reschedule, flight reactions, overload) + E2E. |

---

## How to use this plan

- Work top-to-bottom. Each PR is independently reviewable and leaves `mvn clean install -pl hub` **green**. PRs are deliberately chunky — one coherent capability each, kept few on purpose.
- Every PR cites the design section (`§n`) and decision (`M7-D-xxx`) it implements.
- **M8 and M9 are unbuilt; M4 and M6 are built.** M7 builds and tests end-to-end *now* behind stub ports for the unbuilt deps (`FlightAssignmentPort` → M9, `BarcodePort` → M8) and wires the **real** M4 service interface (confirmed weight, SLA, drop type) and the **real** M6 seam (M7 produces the feed M6 already consumes). Stubs swap for real impls later with no change to the sort engine — mirrors M6's `DaRosterPort` / `HubSortPort` pattern.
- **Package layout** (`CLAUDE.md`): `api/` · `service/` (+ `service/impl/`, `service/port/`) · `domain/` · `repository/` · `events/` (+ `events/payload/`) · `dto/` · `config/`. Cross-module rule: import only another module's **public service interface**, never internals.
- **One physical picture for the whole module:** design §0. Whenever code says `stand`, `flight_bag`, `bag`, `manifest`, picture the shelf, the sack on it, the packing list. (Stand assignment is dynamic — the open bag *is* the directory, M7-D-001.)

---

## What each PR delivers (at a glance)

| PR | Capability when merged | Physical demo |
|----|------------------------|---------------|
| **#1** | A first-mile parcel arrives at the origin hub, gets sorted to a stand, bagged by flight, sealed, manifested, and dispatched to the airport shuttle. | "Box comes off the van → scan in → screen says Stand A-12 → into the Mumbai bag → seal at cutoff → packing list prints → bag leaves." |
| **#2** | A landed parcel at the destination hub is broken out, sorted by `hex→territory→route` into a **route bag** (or a DA-territory bag when no van runs), and **handed to M6's binder** (the stub goes away); plus the same-city shortcut and hub-collect. | "Bag lands → break it → scan each box → South-route Stand D-3 → van loads the whole bag for last mile." |
| **#3** | The hub survives the messy day: low-weight bag reschedule, flight delay/cancel reactions, overload back-pressure, operator console, full virtual-day E2E. | "Flight cancelled → re-bag onto next flight, SLA-checked → nobody silently dropped." |

---

# PR #1 — Foundations + the origin-hub path (the spine)

Implements design §0, §4, §5, §6 (van + self-drop), §7, §14. Decisions `M7-D-001/003/005/008/009/010/011`.

### Read first
- `routing/db/migration/V6_*` (Flyway style) and `routing/service/port/NoOp*Port.java` + `BufferedHubSortPort.java` (the stub-port + `@Primary`-swap pattern to copy).
- `common/kafka/KafkaTopics.java` (`HUB_EVENTS`), `common/kafka/enums/HubEventType.java` (existing 5 values), `common/domain/enums/ShipmentState.java` (the hub states).
- `docs/M8-BARCODE-DESIGN.md` §4 (parcel + bag label payload shapes — what `BarcodePort` returns).
- M4's public shipment service interface (for confirmed weight, SLA/commitment, drop type, dest) — wire real, do **not** import internals.

### What to build
1. **`common.kafka.enums.HubEventType` additions** — `PARCEL_SORTED_FOR_DELIVERY`, `BAG_SEALED`, `MANIFEST_GENERATED`, `BAG_RESCHEDULED`, `HUB_OVERLOAD_ALERT`, `HUB_DISCREPANCY`. Mark `DROP_VAN_HANDOFF` deprecated (`M7-D-004`; not emitted). Add payload records (`HubEventBase` + concrete events implementing `DomainEvent`), Jackson-serializable, keyed by `parcelId`/`bagId`.
2. **`config.ClockConfig`** — `@Bean Clock` (IST); every service injects `Clock`, never `Instant.now()` (`M7-D-011`).
3. **`config.HubProperties`** (`@ConfigurationProperties("hub")`) — underweight thresholds (default), bag-cutoff buffer, overload high-water marks, `destHubTailMinutes` (the Q2 budget placeholder), wave settings.
4. **Stub ports** (`service.port`): `FlightAssignmentPort` (+ deterministic stub: flight + cutoff per dest/ready-time, `M7-D-009`); `BarcodePort` (+ local impl: build bag-QR data, latest-scan lookup, `M7-D-010`). Both config-toggled like M6's No-op ports.
5. **Flyway `V7_1…V7_9`** (`hub/db/migration`) — the §14.3 DDL: `stand` (**no `kind`** — a stand is a bare physical spot, `zone` is a soft floor-area hint only), `flight_bag`, `bag_item`, `bag_manifest`, `stand_reassignment_audit`, `inbound_receipt`, `delivery_bag_item`, `hub_load_snapshot`, + seed of the **physical stand pool** for all 5 hubs (`ON CONFLICT DO NOTHING`, deterministic `md5()::uuid` ids, reusing `grid.cities` UUIDs). **No `sort_plan`/`sort_plan_entry`** — stand assignment is dynamic (M7-D-001), so there is no pre-seeded directory. Indexes per §14.3.
6. **JPA entities + repositories + enums** for all tables (append-only ones immutable + `@PrePersist`, copying `grid`/`routing` style). Enums: `SortDirection`, `StandStatus`(OPEN|OCCUPIED|CLOSED), `BagStatus`, `BagItemStatus`, `ArrivalMode`(derived from M4 state, not input), `DiscrepancyType`, `DeliveryBagItemStatus`. **No `StandKind`.** Key finders: `FlightBagRepository.findByFlightNoAndFlightDateAndDestHubAndStatus(...)` (lazy-create lookup), `StandRepository.findFreeStands(...)` (the shared free-stand pool — any in-service stand with no open bag, ordered by `zone, stand_no`), `BagItemRepository.findByBagId`, etc.
7. **Inbound receiving (§6, origin modes)** — `HubReceivingService.receive(hubId, ref)`: **derives** the arrival mode from the parcel's M4 state (`ArrivalMode.fromState` — no `mode` param, not off the barcode, `M7-D-005`); handles **VAN** (sort off the `AT_ORIGIN_HUB` state) and **SELF_DROP** (`AWAITING_SELF_DROP`); AIRPORT → `UnsupportedArrivalModeException` (PR #2); reconciliation vs van manifest → `inbound_receipt` (+ `HUB_DISCREPANCY` on mismatch).
8. **Sort engine (§7.1)** — `SortService.resolveOutbound(...)`: resolve flight (`FlightAssignmentPort`), open/find the flight bag (which dynamically allocates a free stand on first open, `M7-D-001`), emit `STAND_ASSIGNED`; transition `→ ORIGIN_HUB_PROCESSING`. No directory lookup.
9. **Flight bags (§7.2–7.3)** — `BagService`: lazy create per `(flight,date,dest_hub)` → `BAG_CREATED`; add parcel (`bag_item`, weight from M4) → `IN_TAKEOFF_BAG`; stand-overflow reassign + relabel (`BarcodePort`) + `stand_reassignment_audit` (`M7-D-008`); seal → `bag_manifest` (append-only) + `BAG_SEALED` + `MANIFEST_GENERATED`; dispatch → `DISPATCHED_TO_AIRPORT`.
10. **API + events** — `HubReceivingController` (`POST /hub/{hubId}/receive[/batch]`), `HubBagController` (bags create/add/reassign-stand/seal/manifest); `HubEventProducer`; `ShipmentStateConsumer` (consume `AT_ORIGIN_HUB` as the sort trigger; `autoStartup` wired, dormant until M4 produces — drive directly in tests).

### Verify
`mvn clean install -pl common,orders,hub`. Boot vs local Postgres → Flyway applies `V7_1…V7_9` clean; the physical stand pool is seeded for 5 hubs (no directory). **One parcel**, origin path: receive → open bag (free stand allocated) → into bag → seal → manifest, with M4 states walking `AT_ORIGIN_HUB → … → DISPATCHED_TO_AIRPORT`. Unit tests: dynamic stand allocation, no-free-stand escalation, weight accumulation, stand-overflow relabel, seal/manifest. Each `HubEventType` payload round-trips through Jackson.

---

# PR #2 — Destination hub (dynamic route bags) + the M6 seam + same-city + hub-collect

Implements design §6 (airport mode), §8, §12. Decisions `M7-D-002/004/012`. **This is the integration PR** — it deletes M6's deliver-side stub and makes the destination side as dynamic as the origin: a parcel is grouped into a **route bag** (van) or a **DA-territory bag** (no van), the open bag is the directory, the van loads the whole bag.

### Read first
- `routing/events/HubFeedConsumer.java`, `routing/events/payload/ParcelSortedForDeliveryEvent.java`, `routing/service/port/BufferedHubSortPort.java` — the **exact field shape** M7 must emit so the swap is a no-op for M6's binder (additive fields only).
- M6 design §16 (custody boundary); M6's nightly route plan / `da_cron_schedule` (territory → loop, for `DeliveryRoutePort`).
- M3 serviceability (`GridService.serviceableAt` / `/api/grid/serviceable-at`) for `dest_hex`, and M3's hex → DA-territory assignment (for `TerritoryPort`).

### What to build
1. **Inbound receiving, AIRPORT mode (§6)** — break a landed bag, reconcile vs M9 manifest (stub), consume `HUB_DEST_IN` → `AT_DEST_HUB`.
2. **Flyway `V7_10`/`V7_11`** — `V7_10__create_delivery_bag.sql` (the dest consolidation unit: `bag_kind ROUTE|DA_TERRITORY|ZONE`, `route_plan_id`/`loop_id`/`da_territory_id`/`zone_id`, `current_stand_id`, status `OPEN|SEALED|LOADED|HANDED_OVER`, counts/weight, `manifest_id` — mirror of `flight_bag`); `V7_11__alter_delivery_bag_item.sql` (add `delivery_bag_id`, plus the already-present `da_territory_id`/`route_plan_id`). **No seed** — delivery bags open dynamically (`M7-D-001`).
3. **New ports (`service.port`)** — `TerritoryPort` (M3: `dest_hex → DaTerritory{territoryId, zoneId}`; real adapter over `GridService`, stub for tests) and `DeliveryRoutePort` (M6: `(cityId, territoryId, date) → Optional<DeliveryRoute{routePlanId, loopId}>`; **empty = no van**; real adapter over M6's route-plan service, stub for tests). Both wire real (M3/M6 are built) but ship with a stub for unit tests, like `FlightAssignmentPort`.
4. **Inbound sort ladder (§8.1, `M7-D-012`)** — `SortService.resolveInbound(...)`: `dest_hex` (label `sort_key` + M3 fallback, cached) → `TerritoryPort` → `DeliveryRoutePort`; pick the bag key (**ROUTE** if a route comes back, else **DA_TERRITORY**, with zone-grouping when territories exceed the delivery-stand pool, Q13); transition `→ DEST_HUB_PROCESSING`.
5. **`DeliveryBagService`** — the dest mirror of `BagService`: lazy `openDeliveryBag` per key (allocates a free stand from the shared pool via the same `StandRepository.findFreeStands(...)`, `NoFreeStandException` → overload) → `BAG_CREATED(direction=INBOUND)`; `addParcel` → `delivery_bag_item` + weight/count; `seal` → `bag_manifest` (load list, append-only) + `BAG_SEALED`; M6's `VAN_LOAD` drives the bag `→ LOADED`.
6. **Branch on drop type (§8.2)** — `DA_DELIVERY`: stage + emit **`PARCEL_SORTED_FOR_DELIVERY`** (`M7-D-002`; core shape `(parcelId, cityId, destinationHexId, validDate, sortedAt, slaDeadline)` **plus additive** `daTerritoryId`, `routePlanId|loopId`, `deliveryBagId`, `standNo`) + `DEST_SORT_COMPLETE`. `HUB_COLLECT`: shelf placement → `AWAITING_HUB_COLLECT` + notification command; consume `HUB_COLLECT_COMPLETED` → `HUB_COLLECTED`.
7. **Same-city shortcut (§12)** — detect origin city == dest city at outbound sort → `SAMECITY_OUTBOUND` → skip the flight path → re-enter inbound sort (§8). No M9 involved (good, since M9 is unbuilt).
8. **Confirm `DROP_VAN_HANDOFF` deprecation (`M7-D-004`)** — M7 emits nothing here; M6's `VAN_LOAD` scan drives `HANDED_TO_DROP_VAN`. Note in code + remove any temptation to emit it.
9. **API** — `POST /hub/{hubId}/inbound/break-bag`, `GET /hub/{hubId}/delivery-bags?loop=&date=` (route/territory bags + contents — what a van loads), `GET /hub/{hubId}/staging?loop=` (per-parcel view).

### Verify
`mvn clean install -pl common,hub,routing` green. Dest hub: airport-in → ladder resolves `hex→territory→route` → route bag opened on a free delivery stand → `PARCEL_SORTED_FOR_DELIVERY` **consumed by M6's `HubFeedConsumer` → binds to a loop** (contract/integration test proving the stub→real swap is invisible to M6). **No-van fallback:** `DeliveryRoutePort` empty → DA-territory bag opened, DA hub-collects. Same-city parcel skips the flight path entirely. Hub-collect parcel → `AWAITING_HUB_COLLECT` → `HUB_COLLECTED`. Unit tests: ladder resolution (route present / absent), dynamic delivery-stand allocation + no-free-stand escalation, additive `PARCEL_SORTED_FOR_DELIVERY` round-trips through M6's tolerant reader.

---

# PR #3 — Resilience & ops: reschedule, flight reactions, overload, console, E2E

Implements design §9, §10, §11, §14.2, §18. Decisions `M7-D-006/007`.

### Read first
- Design §9 (SLA-gated reschedule) and §16 Q2 (the timing-budget split — use `HubProperties.destHubTailMinutes` until M9/M10 finalise).
- `common` flight-event payloads (`oneday.flight.events`) and M6's overflow philosophy (`M6-D-017`) for symmetric back-pressure.

### What to build
1. **Low-weight reschedule (§9, `M7-D-006`)** — at cutoff approach, if `bag.weight < threshold`: `FlightAssignmentPort.nextFlightAfter`, check **every** bag parcel's `needed_arrival ≥ later.arrival + destHubTail`; if all safe → move bag, re-resolve stand, regenerate manifest (append-only supersede), emit `BAG_RESCHEDULED`; else keep. `POST /hub/{hubId}/bags/{bagId}/reschedule`.
2. **Flight-status reactions (§10)** — consume `oneday.flight.events` (M9 stub): `DELAYED` (reopen window, pull-in), `DEPARTED` (re-bag stragglers), `LANDED` (trigger inbound), `CANCELLED` (forced re-bag + SLA recheck → breaches to M10/M11). Same re-bag path as §9, involuntary trigger.
3. **Overload back-pressure (§11, `M7-D-007`)** — rolling `hub_load_snapshot` (arrival rate, awaiting-sort, stand occupancy, projected clear-by-cutoff); `HUB_OVERLOAD_ALERT` → M10 + station mgr; advisory booking-throttle signal for M4; escalate-never-discard. `GET /hub/{hubId}/load`.
4. **Operator console API completion (§14.2)** — `GET /hub/{hubId}/bags?direction=&date=` (live open bags = the dynamic directory), `POST .../parcels/{id}/resolve`, any remaining query endpoints.
5. **Simulation / E2E (§18)** — test-only **virtual hub**: synthetic arrivals (van/self-drop/airport) across waves, accelerated clock; pairs with M6's virtual van (van unloads → M7 sorts → `PARCEL_SORTED_FOR_DELIVERY` → M6 binds). Assert invariants: every parcel stands-or-escalates (C1), custody continuity (C12), no SLA silently dropped (C11), reschedule never breaches (C10), same-city skips flight (C14).

### Verify
`mvn clean install -pl common,hub,routing` green. Virtual hub day passes all invariants. Reschedule moves only SLA-safe bags. Flight-cancel forces re-bag with SLA recheck. Overload raises alerts + throttle, drops nothing. Integration with mock M6/M8/M9 consumers.

---

## Deliberately deferred (not in these 3 PRs)

- **Real M8 / M9 wiring** — ships when those modules land; only the stub-port impls swap (`@Primary`), no sort-engine change.
- **Real booking-throttle enforcement in M4** — M7 emits the advisory signal (`M7-D-007`); the throttle *policy* (how hard M4 slows, add-wave vs add-shift) is an ops playbook (H3 / Q7).
- **Finalised timing-budget split (Q2)** — `destHubTailMinutes` is a config placeholder until M7/M9/M10 agree.
- **Operator console UI** — a separate client; M7 serves the API contract only.
- **Label-attachment hardware (Q11)** — DA pocket-printer assumption lives in M8 + the DA app, not M7.

## Dependency & seam summary

```
M4 (built)   ── shipment state AT_ORIGIN_HUB/AT_DEST_HUB, weight, SLA, drop type ─►  M7   (wire real iface)
M3 (built)   ── dest_hex (serviceability) + hex→DA territory (TerritoryPort) ────►  M7   (wire real)
M6 (built)   ── DA territory → delivery route/loop (DeliveryRoutePort, nightly plan) ►  M7  (wire real; empty = no van)
M6 (built)   ◄─ PARCEL_SORTED_FOR_DELIVERY (M7-D-002) — M7 is the real backing for routing's HubSortPort
M6 (built)   ── VAN_UNLOAD scan / van manifest ──────────────────────────────►  M7   (receive + reconcile)
M8 (unbuilt) ◄─ BarcodePort (stub): bag QR build, latest-scan lookup
M9 (unbuilt) ◄─ FlightAssignmentPort (stub): assigned flight + cutoff; flight.events consumer dormant
```

---

*Plan v0.2 — 3 PRs implementing `docs/M7/M7-HUB-DESIGN.md` v0.2. PR1 = origin hub spine (dynamic flight bags), PR2 = destination hub (dynamic route bags via `hex→territory→route`, DA-territory fallback, `M7-D-012`) + M6 seam + same-city, PR3 = resilience/ops + E2E. Each leaves the build green; stubs for unbuilt M8/M9, real wiring for built M3/M4/M6.*
