# M8 — Barcode & Scan Ledger — Design (v0.1)

**Module:** `barcode` · **Depends on:** `common` · **Consumed by:** `orders` (M4), `routing` (M6), `hub` (M7), `sla` (M10)
**PRD:** §10 (binding physical-digital contract), FR-7 · **Resolves:** H1 (label standard). H2/H3 noted, owned elsewhere.

M8 is the **system of record for two physical-world facts**: (1) the **barcode** stuck on a parcel — its lifelong unique id — and (2) every **scan** of that barcode, forever, append-only. Nothing in M8 is clever; the value is that it is *complete and immutable*. Every other module reads state from it or reacts to it.

---

## 1. What M8 owns (and what it does not)

| M8 owns | M8 does **not** own |
|---|---|
| Generating the parcel barcode string (the "parcel ID") | The shipment state machine (M4) |
| The append-only **scan ledger** — one row per physical scan | Van manifest lifecycle (M6 `van_manifest_item`) |
| Publishing `ScanEvent` so M4 advances state | Hub dock/bag/stand logic (M7 `inbound_receipt`) |
| Rendering label payloads (what gets printed) | Deciding *whether* a scan is operationally legal — callers do that |

**The one rule:** *the scan physically happened, so it is recorded — unconditionally, before any business logic.* Legality checks (out-of-order, off-manifest) are the **caller's** job and never block the ledger write. This is already how `routing/CustodyServiceImpl` treats the seam:

> `// 1. The scan physically happened — record it in M8's immutable ledger before anything else.`

---

## 2. The two identities (resolving the current mismatch)

Today the code disagrees with itself, and M8 must settle it:

| Identity | Type | Today's home | What it is |
|---|---|---|---|
| **shipmentId** | `UUID` | `orders.shipments.id` | Internal spine. Immutable, exists from BOOKED. |
| **parcelId (barcode)** | `VARCHAR(30)` | `orders.shipments.parcel_id` | The **printed, scannable string** the DA sticks on the box. Exists only *after* `LABEL_GENERATED`. |

**The current contracts are a deliberate v1 stopgap, not a mistake.** `routing.ScanLedgerPort.VanCustodyScan.parcelId` and `hub.InboundReceipt.parcelId` are typed `UUID` and carry the **shipmentId** — routing states this explicitly:

> `DaFeedConsumer.java:19  // v1: parcelId == shipmentId (no M8 barcode).`
> `DaFeedConsumer.java:38  UUID parcelId = event.parcelId() != null ? event.parcelId() : event.shipmentId();`

Until M8 mints a real barcode, every module works in shipmentId-space and uses the shipment UUID as the parcel key. This is coherent and must not be "corrected" — the manifest join (`findByParcelId`) depends on it.

**Decision D-001 — the ledger spine is `shipmentId` (UUID); `parcelId` (barcode string) is an attribute layered on top.**
Every scan row joins on `shipment_id` — the same UUID routing/hub already pass. The barcode string is denormalised onto each row for field lookups ("scan gun sees `1DD-...`, who is this?"), populated from `LABEL_GENERATED` onward. **Nothing upstream changes**: M8 accepts the UUID they send today as `shipment_id`, and adds the barcode string as a new, initially-null column. Renaming `VanCustodyScan.parcelId → shipmentId` is optional cosmetic cleanup, never a blocker.

> **Where it matters — orders vs. van.** M4 books a shipment → `shipmentId` exists, `parcel_id` is `NULL`. The DA arrives, prints and sticks the label → M8 generates `1DD-BLR-260710-000042`, writes it to the ledger *and* emits it to M4 which fills `shipments.parcel_id`. From then on the hub gun, van driver app, and airport GHA all scan that same string; M8 resolves string → shipmentId on every scan.

---

## 3. Parcel-ID generation (Job 1)

### 3.1 When it fires
The barcode is born at **first physical custody**, per PRD §10.1:

- **DA pickup (first mile):** DA attaches the label at the customer's door. This is the `LABEL_GENERATED` moment.
  > *DA phone hits `POST /api/v1/scan/label` with the shipmentId; M8 mints the string, returns it for the DA app to render/print, writes the ledger row, emits `ScanEvent(LABEL_GENERATED, parcelId)`.*
- **Self-drop:** label is pre-printed on the customer's booking confirmation (booked → they bring it). M8 still mints at book-confirmation time so the customer can print; ledger row written when the **hub counter** scans it in (`SELF_DROP_ACCEPTED`).

### 3.2 Format — Decision D-002
```
1DD-{destHubIATA}-{yyMMdd}-{seq6}      e.g.  1DD-BLR-260710-000042
 └3  └3           └6        └6   = 21 chars, fits VARCHAR(30) with room
```
- **Human-readable + routing-obvious.** A hub sorter reading `...-BLR-...` knows the box flies to Bengaluru without a scan — matches PRD §10.1 "label data includes destination hub".
- `seq6` is a per-hub-per-day counter (append-only counter row, same pattern as `orders.ShipmentRefCounter`).
- Encoded as **Code-128** (D-003 below). The IATA/date prefix is a *convenience for humans*; the string as a whole is the unique key. No routing logic ever parses it — routing hints are re-derived from the shipment, never from the barcode (barcodes outlive re-sorts).

### 3.3 Label standard — Decision D-003 (resolves PRD H1)
| Label | Symbology | Why |
|---|---|---|
| **Parcel label** | **Code-128** (linear) | Cheapest scan guns, universal, 21-char alnum fits trivially. One id, scanned hundreds of times hub→van→DA. |
| **Bag label** | **QR (2D)** | Must hold *two* numbers (flight + physical stand) **plus** packet references (PRD §10.2). Linear can't. Bag QR is M7's render; M8 only stores scans *against* it. |

> **Where it's used — hub.** Hub sorter scans a parcel's Code-128 → M8 resolves shipmentId → M7 tells them the stand/bag. When the bag is full and reassigned (M7 `BagReassignmentService`), M7 **reprints the QR** with the new stand number (H2: *electronic update + reprint*, both). Parcel Code-128s never change — the ledger spine is stable across the re-sort. That's the whole point of D-001.

---

## 4. The scan ledger (Job 2)

### 4.1 Schema — `scan_ledger` (Flyway `V8_1`), **insert-only**
```
id             UUID PK
shipment_id    UUID        NOT NULL   -- spine (D-001), indexed
parcel_id      VARCHAR(30)            -- barcode string, NULL before LABEL_GENERATED, indexed
scan_type      VARCHAR(24) NOT NULL   -- union of ScanEventType + VanScanType (§4.2)
location_type  VARCHAR(16) NOT NULL   -- HUB | VAN | DA | AIRPORT | CUSTOMER_COUNTER
location_id    UUID                   -- hubId / vanId / daId
actor_id       UUID                   -- who held the gun
counterparty_id UUID                  -- the *other* party in a handoff (DA on a VAN_TO_DA)
scanned_at     TIMESTAMPTZ NOT NULL   -- device wall-clock (when it physically happened)
recorded_at    TIMESTAMPTZ NOT NULL   -- server insert time (default now())
detail         JSONB                  -- scan-type-specific extras, else NULL
client_scan_id UUID                   -- device-supplied idempotency key
```
- **Append-only invariant** (repo-wide rule): no `UPDATE`/`DELETE` in code. Optional hardening — one Postgres `RULE`/trigger that raises on update/delete (~3 lines); recommended but not blocking.
- **Idempotency:** partial `UNIQUE (client_scan_id) WHERE client_scan_id IS NOT NULL`. A van driver's app retrying on flaky signal re-sends the same `client_scan_id` → duplicate insert is swallowed, one row stands. Matches routing's "replays are idempotent."

### 4.2 The unified scan-type vocabulary
M8 stores **one** `scan_type` column spanning both existing families — no enum merge needed (they stay in `common`):

| `scan_type` | Source family | Location | Emits `ScanEvent`? |
|---|---|---|---|
| `LABEL_GENERATED` | `ScanEventType` | DA | yes (carries parcelId) |
| `HUB_ORIGIN_IN` | `ScanEventType` | HUB | yes → M4 `AT_ORIGIN_HUB` |
| `SELF_DROP_ACCEPTED` | `ScanEventType` | CUSTOMER_COUNTER | yes → M4 `AT_ORIGIN_HUB` |
| `GHA_ACCEPTANCE` | `ScanEventType` | AIRPORT | yes → M4 `AT_AIRPORT` |
| `HUB_DEST_IN` | `ScanEventType` | HUB | yes → M4 `AT_DEST_HUB` |
| `HUB_COLLECT_COMPLETED` | `ScanEventType` | CUSTOMER_COUNTER | yes → M4 `HUB_COLLECTED` |
| `VAN_LOAD` | `VanScanType` | VAN | no (routing owns manifest) |
| `VAN_TO_DA` | `VanScanType` | VAN→DA | no |
| `DA_TO_VAN` | `VanScanType` | DA→VAN | no |
| `VAN_UNLOAD` | `VanScanType` | VAN→HUB | no |

**Decision D-004 — van scans are recorded, not broadcast.** The four van scans land in the ledger (custody truth) but M8 does **not** publish a `ScanEvent` for them — routing already advances its own manifest and M4 state moves off routing/hub events, not van custody. This confirms routing's Q12/Q14: the **van is a distinct `location_type` (VAN)**, and both van + driver identity ride the scan (`location_id`=vanId, `actor_id`=driverId, `counterparty_id`=daId).

### 4.3 Two ways in (monolith reality)
| Path | Caller | Why |
|---|---|---|
| **Sync port** `ScanLedgerPort.recordVanScan(...)` | in-process: `routing/CustodyServiceImpl`, later `hub` scan-in | Same JVM, inside the caller's TX; must be recorded *before* the caller mutates its own manifest. M8 provides the real `@Primary` bean; routing's `NoOpScanLedgerPort` steps aside. |
| **REST** `POST /api/v1/scan` | physical scanner apps: DA phone, hub gun | The device is not in-process. One endpoint, discriminated by `scan_type`. |

> **Where each path is used.**
> - **Van (sync):** driver taps "load parcel" → `routing` calls `recordVanScan(VAN_LOAD)` → M8 row, *then* routing seals the manifest LOADED. If M8's write is missing there is no custody truth, so it goes first.
> - **Hub (REST or sync):** hub gun scans a box off the pickup van at the dock → `POST /api/v1/scan {HUB_ORIGIN_IN}` → M8 row + `ScanEvent` → M4 flips `AT_ORIGIN_HUB`. (M7's own `inbound_receipt` is its *sortation* record; the *scan* truth is M8's. Reconciling the two is §6.)
> - **DA (REST):** DA phone `POST /api/v1/scan {LABEL_GENERATED}` at the door; and the van-side `VAN_TO_DA`/`DA_TO_VAN` are logged by the **van** driver app (the DA is the counterparty, not the scanner) — matches M6 §11.1.

---

## 5. The M4 seam — `ScanEvent` extension

`orders/ScanEventsConsumer` already consumes `oneday.scan.events` and drives the state machine, with two flagged TODOs M8 must close:

1. **`LABEL_GENERATED` needs the parcelId.** Today `ScanEvent(shipmentId, eventType)` can't carry the barcode string, so the consumer no-ops it.
   **Decision D-005 — extend to `ScanEvent(shipmentId, eventType, parcelId, occurredAt)`** (both new fields nullable; tolerant-reader `@JsonIgnoreProperties` already set). On `LABEL_GENERATED`, M4 sets `shipments.parcel_id = event.parcelId()` (no state transition). All other event types leave `parcelId` null.
2. **ETA-recalc + notify on hub-in** stays M4's TODO, not M8's.

M8 emits the `ScanEvent` **after** the ledger row commits (AFTER_COMMIT, same pattern as `orders/ShipmentEventProducer`) — the ledger is the source of truth; the event is a downstream signal.

---

## 6. Overlaps to reconcile (not v1 blockers)

| With | Overlap | Resolution |
|---|---|---|
| **M7 hub** | `inbound_receipt` also records a per-parcel "taken into custody" fact | M8 = scan truth; M7 = sortation state. M7's dock scan *calls* M8 (`HUB_ORIGIN_IN`/`HUB_DEST_IN`) and keeps its own receipt for stand/bag. No merge; document the two-write. |
| **M6 routing** | `van_manifest_item.status` mirrors custody progression | M6 owns lifecycle/legality; M8 owns the raw scan. Already wired via `ScanLedgerPort`. |
| **M10 sla** | needs scan timestamps per leg | M10 reads the ledger (or subscribes to `ScanEvent`) — read-only consumer. |

---

## 7. Package layout
```
com.oneday.barcode
  api/         ScanController (POST /api/v1/scan, POST /api/v1/scan/label)
  service/     ScanLedgerService (interface) + impl; ParcelIdGenerator
  domain/      ScanLedgerEntry (@Entity, append-only), ParcelIdCounter
  repository/  ScanLedgerRepository (no update/delete methods), ParcelIdCounterRepository
  events/      ScanEventProducer (AFTER_COMMIT → oneday.scan.events)
  adapter/     ScanLedgerPortAdapter implements routing's ScanLedgerPort (@Primary)
```

## 8. Decisions log
- **D-001** Ledger spine = `shipmentId` (UUID); barcode string is a denormalised attribute.
- **D-002** Parcel ID format `1DD-{destHubIATA}-{yyMMdd}-{seq6}`, human-readable, never parsed for logic.
- **D-003** Code-128 for parcels, QR for bags (resolves **H1**).
- **D-004** Van scans recorded in ledger, not published as `ScanEvent`; van is a distinct `location_type`.
- **D-005** Extend `ScanEvent` with `parcelId` + `occurredAt` (closes M4 consumer TODO).

## 9. Out of scope for M8
- **H2** bag reassignment reprint workflow → M7 (`BagReassignmentService`). M8 only guarantees parcel Code-128s survive it.
- **H3** hub-overload throttle → M7 (`HubLoadService` / `HUB_OVERLOAD_ALERT`).
- Print-hardware drivers, scan-gun firmware — the apps render; M8 returns the string/payload.

---
*Next artifact: `M8-IMPLEMENTATION-PLAN.md` (PR breakdown). Open input needed: confirm D-005 field names with whoever owns `common/ScanEvent`, and confirm routing is OK renaming `VanCustodyScan.parcelId` → `shipmentId` (optional).*
