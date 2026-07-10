# M8 — Data Lifecycle: `scan_ledger` & `parcel_id_counter`

**Tables:** `scan_ledger` (`V8_1`), `parcel_id_counter` (`V8_2`) · **Design:** `M8-BARCODE-DESIGN.md` · **Plan:** `M8-IMPLEMENTATION-PLAN.md`

When does each column of the two PR1 tables get **written**, and when does it get **read**? This doc traces that against a real parcel journey, marking which PR activates each write.

> **State of the code (PR1).** Nothing writes a row at runtime yet. The tables exist (empty), the entities define shape, and the repos define access methods — but **no caller invokes them**. `findByShipmentIdOrderByScannedAtAsc`, `findByParcelIdOrderByScannedAtAsc`, and the counter's `insertIfAbsent`/`findByIdWithLock` are *defined, called by nobody*. The first real INSERT arrives in **PR2** (`LABEL_GENERATED`). The map below is "when each column *will* fill" — each row marks the PR that flips it on.

---

## Scenario A — Priya ships a saree, Bengaluru → Delhi (canonical intercity)

Symbols: `S` = shipmentId (exists from BOOKED, M4). Barcode minted at pickup = `1DD-DEL-260710-000042`. `Hb`=BLR hub, `Hd`=DEL hub, `Vp/Vd`=pickup/delivery van, `Dp/Dd`=pickup/delivery DA, `drvP/drvD`=van drivers.

Each scan = **one INSERT into `scan_ledger`**. Reading left to right shows which columns fill:

| # | Business moment | `scan_type` | `location_type` | `location_id` | `actor_id` | `counterparty_id` | `parcel_id` | `client_scan_id` | PR | ScanEvent? |
|---|-----------------|-------------|-----------------|---------------|-----------|-------------------|-------------|------------------|----|-----------|
| 1 | DA sticks label at Priya's door | `LABEL_GENERATED` | `DA` | `Dp` | `Dp` | — | **`1DD-DEL-…042`** ← first set here | DA-app uuid | **PR2** | yes → M4 fills `shipments.parcel_id` |
| 2 | DA hands box to pickup van (cron point) | `DA_TO_VAN` | `VAN` | `Vp` | `drvP` | **`Dp`** | ∅* | — | PR4 | no (D-004) |
| 3 | Van unloads at BLR hub dock | `VAN_UNLOAD` | `VAN` | `Vp` | `drvP` | — | ∅* | — | PR4 | no |
| 4 | Hub gun scans box in | `HUB_ORIGIN_IN` | `HUB` | `Hb` | operator | — | `1DD-DEL-…042` | gun uuid | **PR3** | yes → `AT_ORIGIN_HUB` |
| 5 | GHA accepts bag at airport | `GHA_ACCEPTANCE` | `AIRPORT` | gha | agent | — | `1DD-DEL-…042` | gun uuid | PR3 | yes → `AT_AIRPORT` |
| 6 | DEL hub dock scans in after landing | `HUB_DEST_IN` | `HUB` | `Hd` | operator | — | `1DD-DEL-…042` | gun uuid | PR3 | yes → `AT_DEST_HUB` |
| 7 | Delivery van loads box | `VAN_LOAD` | `VAN` | `Vd` | `drvD` | — | ∅* | — | PR4 | no |
| 8 | Van hands box to delivery DA | `VAN_TO_DA` | `VAN` | `Vd` | `drvD` | **`Dd`** | ∅* | — | PR4 | no |

`id` (auto-UUID), `shipment_id` = **`S` on every row** (the spine), `scanned_at` (device clock) and `recorded_at` (`DEFAULT now()`, DB-set) are populated on **all 8** — omitted above for width.

**`∅*` — the one nuance worth knowing:** the 4 van rows leave `parcel_id` NULL. Routing's `VanCustodyScan` carries only the shipment UUID (the documented `v1: parcelId == shipmentId` contract), not the printed string — so those rows are found via `shipment_id`, not the barcode. Whether PR4 backfills the string via a lookup is a PR4 call; the trail is complete either way because `shipment_id` is on every row.

### The counter's moment (row 1 only)
At `LABEL_GENERATED`, **before** inserting the ledger row, PR2 touches `parcel_id_counter`:
1. `insertIfAbsent('DEL', 2026-07-10)` → creates row `(DEL, 2026-07-10, 1)` **only if Priya's is the first parcel bound for Delhi today** (`ON CONFLICT DO NOTHING` swallows the race).
2. `findByIdWithLock((DEL, 2026-07-10))` → `SELECT FOR UPDATE`, reads `next_seq` = 42, PR2 formats `…-000042`, sets `next_seq = 43`, saves.

So `parcel_id_counter` is **read+written once per parcel, at label time only**. `hub_iata`/`day` are written just once per hub per day (the first parcel); `next_seq` bumps on every label. *(Counter keyed on the **dest** IATA that appears in the barcode — confirmed in PR2.)*

---

## Column-by-column: when written, when NULL, when read

### `scan_ledger`

| Column | Written when | Ever NULL? | Read by |
|--------|-------------|-----------|---------|
| `id` | every insert (auto) | no | — |
| `shipment_id` | every insert | no | `findByShipmentIdOrderByScannedAtAsc` — the audit trail |
| `parcel_id` | rows where the scanner holds the string (label + hub/airport REST scans) | yes, on van scans | `findByParcelIdOrderByScannedAtAsc` — "who touched box `1DD-…`?" |
| `scan_type` | every insert | no | trail readers; PR4 dedup `existsBy…ScanType` |
| `location_type` / `location_id` | every insert (loc_id when known) | loc_id can be NULL | ops "where was it last?" |
| `actor_id` | any human/device scan | rarely NULL | disputes ("which DA/driver?") |
| `counterparty_id` | **only van↔DA handoffs** (rows 2, 8) | yes, most rows | M11 lost-parcel dispute (van driver vs DA) |
| `scanned_at` | every insert (device time) | no | M10 SLA per-leg timing; trail ordering |
| `recorded_at` | every insert (`DEFAULT now()`) | no | clock-skew forensics (device vs server) |
| `client_scan_id` | when device supplies an idempotency key | yes (van scans) | unique-index dedup on insert (retry safety) |

### `parcel_id_counter`
Written **and** read **only at `LABEL_GENERATED`** (PR2). No other module ever touches it. Not read on any downstream scan.

---

## Scenario B — branches that fill *different* columns

**B1 — self-drop (customer walks into BLR hub).** Rows 1–3 vanish; instead one row: `scan_type=SELF_DROP_ACCEPTED`, `location_type=CUSTOMER_COUNTER`, `location_id=Hb`, `actor_id=`counter clerk. → `AT_ORIGIN_HUB`. The only path that writes `CUSTOMER_COUNTER` on the *origin* side.

**B2 — hub-collect (recipient picks up at DEL hub).** Rows 7–8 vanish; one row: `scan_type=HUB_COLLECT_COMPLETED`, `location_type=CUSTOMER_COUNTER`, `location_id=Hd`. → `HUB_COLLECTED` (terminal).

**B3 — flaky signal at the meeting point (the dedup fetch).** The van driver's app fires `VAN_TO_DA` (row 8), loses signal, retries. If it resends the same `client_scan_id`, the `uq_scan_ledger_client` unique index rejects the second insert → **one row stands**. If the van path supplies no `client_scan_id`, PR4 instead does a **read first** — `existsByShipmentIdAndScanType(S, 'VAN_TO_DA')` → true → skip. Either way, the retry is where a *fetch* guards a *write*.

---

## When does data get fetched — the read moments

1. **Every label generation** → `findByIdWithLock` on the counter (read-to-increment). *(PR2)*
2. **Every van scan** → possibly `existsBy…` dedup read before insert. *(PR4)*
3. **Lost-parcel / RTO investigation** → `findByParcelIdOrderByScannedAtAsc` — the station manager's "who last touched box `1DD-DEL-…042`". *(on demand, M11)*
4. **SLA per-leg timing** → M10 reads `scanned_at` off the trail (or consumes the `ScanEvent`). *(M10)*
5. **NOT** the M4 state machine — it never queries these tables; it reacts to the `ScanEvent` on `oneday.scan.events`. The ledger is the durable *truth*; the event is the *signal*.

**Net:** writes are **append-heavy, read-light** — 8 inserts vs. a handful of reads, most reads being either the counter bump (hot path) or rare forensic trail lookups (cold path). That read/write shape is why `scan_ledger` is indexed on `(shipment_id, scanned_at)` and `parcel_id`, and why it's insert-only with a trigger instead of carrying update machinery.
