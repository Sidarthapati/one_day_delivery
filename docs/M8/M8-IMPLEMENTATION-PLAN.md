# M8 — Barcode & Scan Ledger — Implementation Plan (v0.1)

**Module:** `barcode` · **Design:** `M8-BARCODE-DESIGN.md` · **Branch:** `M8`
**4 PRs.** Each is independently reviewable, ships its own tests, and leaves the app assembling green. The ledger **write engine** is built once (PR2); PR3 and PR4 add the two remaining entry doors over it (design §4.3 "two ways in").

Build order is chosen so nothing waits on a not-yet-built seam: the ledger exists before anything writes (PR1), the first real writer is the one M8 fully owns (PR2, label), then the two external seams that flip live consumers — M4 state (PR3) and routing custody (PR4).

| PR | Deliverable | Flips live |
|----|-------------|-----------|
| **PR1** | Append-only ledger table + parcel-id counter (schema, domain, repo) | nothing (foundation) |
| **PR2** | Parcel-ID generation + `ScanLedgerService` write engine + `LABEL_GENERATED` → M4 fills `shipments.parcel_id` | DA label path |
| **PR3** | REST `POST /api/v1/scan` for hub/airport/counter scans → async `ScanEvent` → M4 state machine | M4 state advances on real scans |
| **PR4** | Sync `ScanLedgerPortAdapter` (`@Primary`) replaces routing's `NoOpScanLedgerPort` + dedup guard | van custody truth is now immutable |

---

## PR1 — Ledger foundation (schema + domain + repo)

**Business scenario.** A ₹1,200 B2B shipment goes missing between the origin hub dock and the takeoff bag. The station manager needs to answer, six weeks later, "who physically last touched box `1DD-BLR-260710-000042`, when, and where?" That question is unanswerable unless *every* scan is stored the instant it happens and *can never be edited*. PR1 builds the drawer that receipt goes in — before anything is allowed to write to it.

**Scope**
- Flyway `V8_1__create_scan_ledger.sql` — `scan_ledger` table (design §4.1), insert-only. Indexes on `shipment_id` and `parcel_id`. Partial `UNIQUE (client_scan_id) WHERE client_scan_id IS NOT NULL`.
- Flyway `V8_2__create_parcel_id_counter.sql` — `parcel_id_counter (hub_iata, day, next_seq)` (per-hub-per-day sequence, mirrors `orders.ShipmentRefCounter`).
- `domain/ScanLedgerEntry` — `@Entity`, all columns `updatable = false`, no setters beyond builder. `domain/ParcelIdCounter`.
- `repository/ScanLedgerRepository` — **read + insert only**; deliberately expose no `delete*`/`save`-for-update methods. `repository/ParcelIdCounterRepository` with `findByHubIataAndDayForUpdate` (pessimistic lock).
- Optional hardening: `V8_1` includes a Postgres `RULE` raising on `UPDATE`/`DELETE` (~3 lines) — the invariant enforced in the DB, not just convention.

**Where it's used** — nobody yet. This PR only makes the immutable store exist. The station-manager query above becomes a one-line `findByParcelIdOrderByScannedAt` once rows land.

**Tests** — `@DataJpaTest`: insert an entry, assert read-back; assert the append-only rule rejects an `UPDATE` (if the DB rule is included); counter increments monotonically under concurrent `findForUpdate`.

---

## PR2 — Parcel-ID generation + write engine + M4 label seam

**Business scenario.** A DA reaches a customer's door in Koramangala for a first-mile pickup. She weighs the box, opens the DA app, taps "Generate Label." A sticker prints — `1DD-BLR-260710-000042` — she slaps it on the box. That string is now the parcel's identity for its entire life: every hub, van, airport, and delivery scan keys off it. On the customer's tracking page, "Label generated" appears and the tracking id becomes visible. Technologically: the DA app calls M8, M8 mints the string, writes the first ledger row, and tells M4 to stamp `shipments.parcel_id`.

**Scope**
- `service/ParcelIdGenerator` — format `1DD-{destHubIATA}-{yyMMdd}-{seq6}` (design D-002); resolves `destHubIATA` from the shipment's dest hub; `seq6` from `ParcelIdCounter` under lock.
- `service/ScanLedgerService` (interface) + impl — **the write engine, built once here**: `record(ScanCommand)` → dedup check (design §4.1 idempotency; and the latent-replay guard from `M8-BARCODE-DESIGN §6` — dedup on `client_scan_id`, or `(shipment_id, scan_type)` for once-only scans) → insert `ScanLedgerEntry` → return. Reused verbatim by PR3 and PR4.
- `events/ScanEventProducer` — AFTER_COMMIT publish to `oneday.scan.events` (mirror `orders/ShipmentEventProducer`).
- `api/ScanController#label` — `POST /api/v1/scan/label {shipmentId}` → generate id, `record(LABEL_GENERATED, location=DA)`, emit `ScanEvent(LABEL_GENERATED, parcelId)`, return the string for the app to print.
- **`common` change (D-005):** extend `ScanEvent(shipmentId, eventType)` → `ScanEvent(shipmentId, eventType, parcelId, occurredAt)` — both new fields nullable, tolerant reader already set.
- **`orders` change:** `ScanEventsConsumer` `LABEL_GENERATED` branch stops no-op'ing — sets `shipments.parcel_id = event.parcelId()` (no state transition). Closes the existing `// TODO: handle once ScanEvent is extended with the parcelId field`.

**Where it's used** — **DA** (mints the label), **orders** (stores it, shows it on tracking). Hub/van don't scan yet — they will in PR3/PR4, all keyed on this string.

**Tests** — id format + uniqueness under concurrency; replayed label call is idempotent (one ledger row, same string); `orders` consumer fills `parcel_id` and does **not** move state on `LABEL_GENERATED`.

---

## PR3 — Lifecycle scan ingestion (REST → M4 state)

**Business scenario.** The pickup van backs into the origin-hub dock at 11:40. The dock operator runs a scan gun down the line of boxes; each beep is a `HUB_ORIGIN_IN`. Within seconds the customer's tracking flips to "Arrived at origin hub" and the SLA clock for the first leg stops. Later that box gets `GHA_ACCEPTANCE` at the airport, `HUB_DEST_IN` at Bengaluru, and — for a hub-collect customer — `HUB_COLLECT_COMPLETED` when they walk up to the counter. Every beep is one REST call that writes an immutable row *and* nudges the M4 state machine.

**Scope**
- `api/ScanController#scan` — `POST /api/v1/scan {shipmentId, scanType, locationType, locationId, actorId, scannedAt, clientScanId, detail?}`. One door, discriminated by `scanType` (design §4.2). Validates `scanType ∈ ScanEventType` (van types are rejected here — they come via the sync port, PR4).
- Reuses `ScanLedgerService.record(...)` from PR2 → insert + AFTER_COMMIT `ScanEvent`.
- **No `orders` change** — `ScanEventsConsumer` already maps `HUB_ORIGIN_IN/SELF_DROP_ACCEPTED/GHA_ACCEPTANCE/HUB_DEST_IN/HUB_COLLECT_COMPLETED` → states. This PR is what finally *feeds* that consumer; the queue stops being empty.
- Auth: gate to hub-operator / GHA / counter roles (follow M4's `Authz` helper pattern; ADMIN always allowed under `!prod`).

**Where it's used** — **hub** (dock gun: `HUB_ORIGIN_IN`, `HUB_DEST_IN`), **airport/GHA** (`GHA_ACCEPTANCE`), **hub counter** (`SELF_DROP_ACCEPTED`, `HUB_COLLECT_COMPLETED`), **orders** (state advances, customer tracking updates), **sla** (leg clocks start/stop off the resulting `ScanEvent`).

**Tests** — each `ScanEventType` writes a row + emits the right event; van type via REST → `400`; replay via same `clientScanId` → one row; end-to-end: scan → `ScanEvent` → M4 transitions (reuse the `oneday.scan.events` test rig from `orders`).

---

## PR4 — Van custody port (replace NoOp) + dedup guard

**Business scenario.** A van driver on the 06:30 delivery loop pulls up to a cron meeting point. He scans a box off the van and hands it to the waiting DA — that's a `VAN_TO_DA`. If his signal drops and the app retries, the scan fires twice. The custody record must show *exactly one* hand-off from van `V-17`, driver `D-42`, to DA `D-88` at 06:47 — not two, and it must never be editable, because this is the evidence in a lost-parcel dispute between the van driver and the DA. Technologically: routing already calls `ScanLedgerPort.recordVanScan(...)`; PR4 makes that call hit a real immutable ledger instead of a log line, and makes the retry a no-op.

**Scope**
- `adapter/ScanLedgerPortAdapter implements routing.ScanLedgerPort`, `@Primary` — maps `VanCustodyScan` → `ScanCommand` (`location_type=VAN`, `location_id=vanId`, `actor_id=driverId`, `counterparty_id=daId`; `shipment_id` = the UUID routing passes, per design D-001) → `ScanLedgerService.record(...)`.
- **Dedup guard (latent bug #1, design §6):** van scans carry no `clientScanId`, so dedup on `(shipment_id, scan_type)` — each parcel gets each van scan once per loop. A replayed `VAN_TO_DA` finds the existing row and returns without inserting.
- **routing change:** `NoOpScanLedgerPort`'s `@Primary`/default status yields to M8's adapter when the `barcode` module is on the classpath (M8's bean wins; NoOp stays as the fallback for routing-only test slices). `VAN_LOAD/VAN_TO_DA/DA_TO_VAN/VAN_UNLOAD` are **not** re-published as `ScanEvent` (design D-004) — routing owns its manifest lifecycle.

**Where it's used** — **van** (driver app originates all four scans via routing's `CustodyServiceImpl`), **routing** (custody truth now durable, `HandoffService` reconciliation reads real rows), **exceptions/M11** (lost-parcel disputes query the immutable trail).

**Tests** — each of the four van scans writes a row with van+driver+DA identity; duplicate `VAN_TO_DA` → one row (dedup); `CustodyServiceImpl` still advances the manifest correctly with the real adapter wired (extend `CustodyServiceImplTest`); app assembles with M8's adapter winning over NoOp.

---

## Cross-cutting

- **`common` touch is one field add** (PR2, `ScanEvent`) — coordinate with the `common` owner; tolerant-reader annotations mean no consumer breaks.
- **Append-only is the load-bearing invariant** — no PR introduces an update/delete path on `scan_ledger`. Reviewer's first check on every M8 PR.
- **Idempotency is centralised** in `ScanLedgerService` (PR2), so PR3 (REST replay) and PR4 (van retry) inherit it for free.
- **No new dependency.** Code-128/QR are *rendered by the device apps* from the string/payload M8 returns; M8 ships no barcode-imaging library (design §9).

## Verification (fold into PR4, or a thin PR5 if preferred)
Boot the app against the dev DB and drive one parcel end-to-end: label (PR2) → `HUB_ORIGIN_IN` (PR3) → `VAN_LOAD`/`VAN_TO_DA` (PR4) → assert `scan_ledger` has the full ordered trail and `shipments.state`/`parcel_id` moved. This is the "station-manager query" from PR1, now answerable.
