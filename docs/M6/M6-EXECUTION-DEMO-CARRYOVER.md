# M6 Execution Demo → Code Carryover Ledger

| Field | Value |
|-------|-------|
| **Demo branch** | `demo/m6-execution` (off `f-m6-design`) |
| **Target branch** | `f-m6-design` (the real M6 line) |
| **Purpose** | The execution-time demo (live "run the day" on the map) surfaced real bugs the way the planning demo did. Track **CARRY OVER** (real fixes that must land on `f-m6-design`) vs **DEMO-ONLY** (driver/UI glue that stays here). |
| **What the demo proves** | RabbitMQ feed → binding → telemetry/live-map (incl. VAN_RUNNING_LATE) → custody lifecycle (LOAD→DELIVER/COLLECT→RETURN/RECONCILED), end-to-end over CloudAMQP + cloud DB + Hetzner OSRM. Verified: 30 parcels published → bound to 5 vans → delivered 20 / collected 10 → all loops reconciled. |

---

## 🔴 CARRY OVER — real fixes (must land on `f-m6-design`)

### 0. Intraday re-plan binds onto the SUPERSEDED plan — two latent bugs (CRITICAL for override path)
Symptom in the demo: prepare → run works; **Re-prepare → run gives `bound=0` and "Driving 0 loops"** (vans never move, only the hub pulses). The new parcels bind, but onto the *previous* plan's manifests, while everything that filters by the live plan id sees nothing. Two independent causes, both real (the nightly path with one plan/day hides them; an **override / re-approve for the same date** triggers them):

- **(a) `VanManifestServiceImpl.contextCache` keyed by `cityId|date`, never evicted.** Once a parcel binds for a day, the cached `PlanCtx` (stops + `hexToDa` territory map + cron) is frozen. A re-approval produces a new plan, but the consumer keeps binding against the **superseded** plan's stops/territories.
  - **Fix applied:** resolve the live APPROVED plan first (cheap, indexed `findByCityIdAndValidForDateAndStatus(... APPROVED).max(revision)`) and key the cache by **`plan.getId()`** — a new plan ⇒ new key ⇒ cache miss ⇒ rebuild. `buildContext` now takes the resolved `RoutePlan`. (`gridDataAdapter.hexToDa` is itself uncached, so the rebuild is fresh.)
  - **Why CARRY OVER:** production-path correctness bug for the intraday override flow (M6-D, station-manager re-approve).
- **(b) `van_manifest` is keyed by `(van_id, loop_index, valid_date)` — no plan.** `V6_14 uq_van_manifest_van_loop_date` + `lockByVanLoopDate`/`findByVanIdAndLoopIndexAndValidDate` (used in 10+ places: telemetry, custody, handoff, recovery, driver app) all assume **one plan per van per day**. On a re-plan, van ids + loop indices repeat, so `lockOrCreate` **reuses the prior plan's manifest row** (whose `route_plan_id` points at the now-superseded plan). Binds pile onto it; the live plan's manifests stay empty.
  - **Decision (RESOLVED 2026-06-23 — Option B + PLANNED reconciliation, `M6-D-022`):** keep the `(van,loop,date)` key (it is the *physical* "one van/loop/day" identity; re-keying by plan would orphan in-custody parcels). Instead `route_plan_id` is made **mutable** and `VanManifestService.reconcileToLivePlan(cityId,date)` runs inside `approve`/`override`: re-points the day's manifests to the live plan, re-routes **PLANNED** items against the new plan, **keeps** loaded items whose stop survives, **escalates** (`LOOP_OVERFLOW`, frozen) loaded items whose stop vanished. `lockOrCreate` also re-points lazily as a race backstop. No-op on the common path (plans finalized before binding starts). Full stop-by-stop re-route of *loaded* vans is deferred (freeze-and-escalate in v1). See `docs/M6/M6-ROUTING-DESIGN.md §10.1`.
  - **Demo-only mitigation (still on demo branch):** `DemoExecutionService.resetDay(cityId,date)` wipes the day's manifests/items/handoffs/live-status at the start of every `run-day`. (Now redundant with the real reconciliation, but harmless for the compressed-time demo where the same date is replayed many times.)
- **Verified:** Run1 `bound=45`; Re-prepare→Run2 `bound=45`, **all 45 items on the new APPROVED plan, 0 on superseded**. Production reconciliation covered by `VanManifestServiceImplTest.reconcileToLivePlan_*` (repoint + reroute PLANNED + keep/escalate loaded).
- [x] (a) cache-by-plan-id ported to `f-m6-design` (2026-06-22)
- [x] (b) intraday-override manifest ownership **decided + implemented** (2026-06-23, Option B + PLANNED reconciliation, `M6-D-022`)

### 0b. Demo feeder drew DAs from all cron rows, incl. drop-and-flag-deferred vertices
- **File:** `routing/.../demo/DemoExecutionService.feedParcels` (demo-only).
- **Change:** restrict the feed pool to DAs whose `hex_vertex_id` is an actual **stop** (`route_plan_stop` MEETING_VERTEX), not just any `da_cron_schedule` row — so every published parcel is bindable even when the solver defers far corners. (With shared-vertex set-cover, many DAs map to one stop, so this rarely shrinks the pool but is correct.)

### 1. RabbitMQ consumers broken — `DefaultClassMapper` trusted-packages wildcard (CRITICAL)
- **File:** `common/src/main/java/com/oneday/common/kafka/MessagingConfig.java`
- **Bug:** `DefaultClassMapper.setTrustedPackages("com.oneday.*")`. `DefaultClassMapper` does **not** do prefix/wildcard matching — it only honours the exact `*` (trust-all) token or **exact** package names. The literal `"com.oneday.*"` matched **no** package, so the `__TypeId__` deserialization of **every** `com.oneday.*` event was rejected. Against a real broker this broke **all** `@RabbitListener` consumers (hub, da, and cron events all failed with `IllegalArgumentException: ... not in the trusted packages [java.util, java.lang, com.oneday.*]` → retries exhausted → DLQ). The build is green and the unit tests pass because nothing exercises real-broker deserialization — this only shows up at runtime.
- **Fix applied:** `setTrustedPackages("*")` (trust-all — the documented escape hatch for an in-house monolith; the `trustedPackages()` helper updated to match). After the fix, M6 binding worked (bound=30) and cron consumers stopped DLQ-ing.
- **Why CARRY OVER:** this is a `common`-module bug that disables RabbitMQ consumption across the whole platform on any real broker — not demo-specific.
- **Follow-up:** if package-scoped trust is wanted later, either enumerate exact package names, or switch to the converter's `DefaultJackson2JavaTypeMapper` (which supports `com.oneday.*` patterns) instead of `DefaultClassMapper`.
- [x] ported to `f-m6-design` (2026-06-22)
- [ ] real-broker integration test (publish→consume one event per stream) added

### 2. `grid` `HexDemandSnapshotRepository.countByHexIdInAndSnapshotDate`
- **File:** `grid/src/main/java/com/oneday/grid/repository/HexDemandSnapshotRepository.java`
- **Change:** added the derived count finder (was only on `demo/m6-planning`; the ported grid `DemoController.demand-count` needs it). Harmless real Spring Data method.
- [ ] ported to `f-m6-design`

### 3. UI: dropped the now-harmful `activateAssignments` step
- **File:** `demo-ui/src/App.jsx` (`handleGenerateTerritories`)
- **Why:** on `f-m6-design` `AssignmentStatus.ACTIVE` is **deprecated/collapsed into APPROVED** — `GridServiceImpl.getDaTerritories` (and the grid map read) filter by **APPROVED**. The planning demo's `/api/demo/activate` (APPROVED→ACTIVE) would move rows **out** of APPROVED and make M6 see **zero** territories. Removed the call from the planning flow; the execution flow never calls it. (This is the real-system resolution of carryover item #3 from the planning ledger: M6 reads APPROVED, there is no separate go-live flip.)
- **Note:** the ported grid `DemoController.activate` endpoint is now dead on this branch — leave it unused or delete it when porting.
- [ ] decision confirmed on `f-m6-design` (no activate step; M6 reads APPROVED)

---

## 🟢 DEMO-ONLY — stays on `demo/m6-execution`

| Item | File(s) | Note |
|------|---------|------|
| Run-the-day driver | `routing/src/main/java/com/oneday/routing/demo/{DemoExecutionService,DemoExecutionController,DemoLog}.java` | `@Profile("!prod")`. Publishes synthetic `ParcelSortedForDelivery`/`DaParcelPickedUp` to the real exchanges (CloudAMQP) so the app's own consumers bind them, then steps telemetry + custody scans per van on a background tick. Single run at a time; in-memory event feed. |
| Execution UI | `demo-ui/src/components/{ExecutionView,ExecutionMap}.jsx`, `demo-ui/src/utils/buildRoutes.js`, execution calls in `demo-ui/src/api/routingApi.js`, the Planning/Execution toggle in `App.jsx` | Live map: moving van markers (lateness-coloured), stats strip, RabbitMQ feed panel. Execution runs for **today** (telemetry resolves manifests by today's date), distinct from the planning tab's tomorrow. |
| Ported planning glue | app `routing:` yaml block + WAN hikari/batching; `DemoSecurityConfig` `/routing/**`; grid `DemoController` + `DemoHexDemandRequest` | Ported from `demo/m6-planning`. **Did NOT** copy that branch's app `application.yml` wholesale — it still had the pre-RabbitMQ **Kafka** block; only the `routing:` block + WAN tuning were merged in, RabbitMQ kept. |

## ⚠ Known benign noise (not a bug to fix here)
- M4's dormant `orders.hub`/`orders.da`/`orders.cron` consumers bind `#` and try to coerce M6's provisional payloads (and cron events) into their own `HubEvent`/etc. types → `MessageConversionException` → those copies DLQ on `orders.*`. M6's own `routing.hub`/`routing.da` consumers bind correctly. This is the **same** "feed queues bind `#`, tighten to routing keys before M5/M7 live" open item already tracked in `M6-IMPLEMENTATION-PLAN.md` — surfaced, not introduced, by the demo.
