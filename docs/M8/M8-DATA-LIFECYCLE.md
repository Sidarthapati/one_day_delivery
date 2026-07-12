# M8 — Data Lifecycle: `scan_ledger` & `parcel_id_counter`

**Tables:** `scan_ledger` (`V8_1`, incl. `bag_id`), `parcel_id_counter` (`V8_2`) · **Design:** `M8-BARCODE-DESIGN.md` · **Plan:** `M8-IMPLEMENTATION-PLAN.md`

When does each column of the M8 tables get **written**, and when does it get **read**? This doc traces that against the full parcel journey — including the **parcel → bag → parcel** transition in the middle, where the unit being scanned is the *bag*, not the parcel.

> **State of the code (PR1).** Nothing writes a row at runtime yet. The tables exist (empty), the entities define shape, the repos define access. The first real INSERT arrives in **PR2** (`LABEL_GENERATED`). The map below is "when each column *will* fill." `bag_id` and the 3 bag/shuttle/delivery scan types below are **added work** on top of the original PR1 scope (see "What changed" at the bottom).

---

## Scenario A — Priya ships a saree, Bengaluru → Delhi (canonical intercity)

Symbols: `S` = shipmentId. Barcode = `1DD-DEL-260710-000042`. `B` = the takeoff **bag** S rides in. `Hb`=BLR hub, `Hd`=DEL hub, `Vp/Vd`=pickup/delivery van, `Sh`=shuttle (hub↔airport), `Dp/Dd`=pickup/delivery DA.

The **unit** column is the key addition: a *parcel* scan reads S's own barcode; a *bag* scan reads the bag QR and **fans out to one row per parcel in the bag** (S is just one of them). Every row still carries `shipment_id = S`.

| # | Business moment | `scan_type` | unit | `location_type` | `parcel_id` | `bag_id` | → M4 state |
|---|-----------------|-------------|------|-----------------|-------------|----------|-----------|
| 1 | DA sticks label at Priya's door | `LABEL_GENERATED` | parcel | `DA` | **`1DD-DEL-…042`** ← set here | — | (sets `shipments.parcel_id`) |
| 2 | DA hands box to pickup van | `DA_TO_VAN` | parcel | `VAN` | ∅* | — | — *(custody; state via M6)* |
| 3 | Van unloads at BLR hub dock | `VAN_UNLOAD` | parcel | `VAN` | ∅* | — | — *(custody)* |
| 4 | Hub gun scans box in | `HUB_ORIGIN_IN` | parcel | `HUB` | `1DD-DEL-…042` | — | `AT_ORIGIN_HUB` |
| — | **sortation: parcels → bag `B`** (M7) | | parcel→bag | | | | `ORIGIN_HUB_PROCESSING`→`IN_TAKEOFF_BAG` *(M7)* |
| 5 | 🆕 Bag scanned out to shuttle | `HUB_ORIGIN_OUT` | **bag** | `HUB` | ∅ | **`B`** | `DISPATCHED_TO_AIRPORT` |
| 6 | GHA accepts + scans bag | `GHA_ACCEPTANCE` | **bag** | `AIRPORT` | ∅ | **`B`** | `AT_AIRPORT` |
| — | fly + land | | | | | | `DEPARTED`→`LANDED` *(M9)* |
| 7 | 🆕 Dest shuttle scans bag | `DEST_SHUTTLE_IN` | **bag** | `SHUTTLE` | ∅ | **`B`** | `DISPATCHED_TO_HUB` |
| 8 | Dest hub scans bag in | `HUB_DEST_IN` | **bag** | `HUB` | ∅ | **`B`** | `AT_DEST_HUB` |
| — | **debagging: bag `B` → parcels** (M7) | | bag→parcel | | | | `DEST_HUB_PROCESSING` *(M7)* |
| 9 | Box scanned onto delivery van | `VAN_LOAD` | parcel | `VAN` | ∅* | — | — *(custody; `HANDED_TO_DROP_VAN` via M5)* |
| 10 | Van scans, hands to DA | `VAN_TO_DA` | parcel | `VAN` | ∅* | — | — *(custody; `DROP_COLLECTED`)* |
| 11 | 🆕 DA scans before handing to Priya's recipient | `DELIVERED` | parcel | `DA` | `1DD-DEL-…042` | — | `DROPPED` *(co-gated with delivery OTP)* |

`id` (auto-UUID), `shipment_id = S` (every row), `actor_id`/`counterparty_id`, `scanned_at` (device clock), `recorded_at` (`DEFAULT now()`) fill exactly as before — omitted for width. `client_scan_id` on the REST scans (1, 4–8, 11); absent on the 4 van scans.

**🆕 = newly promoted to `ScanEventType`** — these three drive an M4 state transition directly (your decision). The 4 van scans stay **custody-only** (recorded, no `ScanEvent`, D-004). Bag scans (5–8) and M7/M9's internal-processing transitions (bagging, flight) are **complementary**: the scan owns the *custody-transfer* state (`DISPATCHED_*`, `AT_*`), M7/M9 own the *internal* states (`*_PROCESSING`, `DEPARTED`/`LANDED`). This split is the M7/M9 coordination the promotion requires.

**`∅*` (van rows):** `parcel_id` NULL because routing's `VanCustodyScan` passes only the shipment UUID (`v1: parcelId == shipmentId`), not the printed string — found via `shipment_id`.

**`∅` + `bag_id=B` (bag rows 5–8):** the gun reads the **bag QR**, not S's barcode, so `parcel_id` is NULL; the row is one of **N fanned-out rows** (one per parcel in `B`), all sharing `bag_id = B`, `scan_type`, `scanned_at`. **M7 owns bag membership**, so M7 drives the fan-out — it iterates the bag's parcels and records one M8 scan each. `bag_id` is what later answers *"which bag was this parcel in / what else flew with it."*

### The counter's moment (row 1 only)
At `LABEL_GENERATED`, **before** the ledger row, PR2 touches `parcel_id_counter`:
1. `insertIfAbsent('DEL', 2026-07-10)` → creates `(DEL, 2026-07-10, 1)` if Priya's is the first Delhi-bound parcel today (`ON CONFLICT DO NOTHING`).
2. `findByIdWithLock` → `SELECT FOR UPDATE`, reads `next_seq`=42, formats `…-000042`, sets 43.

Read+written **once per parcel, at label time only**. `next_seq` bumps every label; the row is created once per (dest-hub, day).

---

## Column-by-column: when written, when NULL, when read

### `scan_ledger`

| Column | Written when | Ever NULL? | Read by |
|--------|-------------|-----------|---------|
| `id` | every insert (auto) | no | — |
| `shipment_id` | every insert | no | `findByShipmentIdOrderByScannedAtAsc` — the trail |
| `parcel_id` | parcel scans where the scanner holds the string (1, 4, 11) | **yes** — on van scans *and* all bag scans | `findByParcelIdOrderByScannedAtAsc` |
| `bag_id` 🆕 | **bag scans only** (5–8) | yes — on all parcel/van scans | "what else was in this bag" join; M7 reconciliation |
| `scan_type` | every insert | no | trail readers; van dedup `existsBy…ScanType` |
| `location_type`/`location_id` | every insert | loc_id can be NULL | ops "where is it?" |
| `actor_id` | any human/device scan | rarely | disputes |
| `counterparty_id` | **van↔DA handoffs only** (2, 10) | mostly | M11 lost-parcel dispute |
| `scanned_at` | every insert (device time) | no | M10 SLA per-leg; ordering |
| `recorded_at` | every insert (`DEFAULT now()`) | no | clock-skew forensics |
| `client_scan_id` | REST scans that supply a key | yes (van scans) | unique-index dedup |

### `parcel_id_counter`
Written **and** read **only at `LABEL_GENERATED`** (PR2). No other module touches it.

---

## Scenario B — branches

**B1 — self-drop (customer walks into BLR hub).** Rows 1–3 vanish; one row: `SELF_DROP_ACCEPTED`, `CUSTOMER_COUNTER`, `Hb` → `AT_ORIGIN_HUB`. The bag legs (5–8) are identical.

**B2 — hub-collect (recipient picks up at DEL hub).** Rows 9–11 vanish; one row: `HUB_COLLECT_COMPLETED`, `CUSTOMER_COUNTER`, `Hd` → `HUB_COLLECTED`.

**B3 — same-city (no flight).** The whole bag section (5–8) collapses — no shuttle, no GHA. `HUB_ORIGIN_IN` → straight to `VAN_LOAD` for the local delivery.

**B4 — flaky signal (dedup).** Retried scan with the same `client_scan_id` → `uq_scan_ledger_client` rejects the dupe. Van scans (no key) → PR4 `existsByShipmentIdAndScanType` read-guard.

---

## When does data get fetched

1. **Every label** → `findByIdWithLock` on the counter. *(PR2)*
2. **Every van / bag-fan-out scan** → `existsBy…` dedup read before insert. *(PR3/PR4)*
3. **Lost-parcel / RTO** → `findByParcelIdOrderByScannedAtAsc` — the full trail, incl. `bag_id` to see co-travellers. *(M11)*
4. **SLA per-leg** → M10 reads `scanned_at` / consumes the `ScanEvent`. *(M10)*
5. **NOT** the M4 state machine — it reacts to `ScanEvent` on `oneday.scan.events`, never queries the table.

**Net:** append-heavy, read-light — but a **bag scan is now N inserts, not one** (fan-out), so origin-hub-out and dest-side scans are the write-volume peaks. That's why the fan-out is M7-driven (it owns the membership) and why `bag_id` is indexed alongside `shipment_id`.

---

## What changed vs. the original PR1 scope (this revision)

Driven by walking the *full* physical flow:

1. **3 scan types promoted to `common.ScanEventType`** — `HUB_ORIGIN_OUT` (→`DISPATCHED_TO_AIRPORT`), `DEST_SHUTTLE_IN` (→`DISPATCHED_TO_HUB`), `DELIVERED` (→`DROPPED`, co-gated with OTP). Touches the shared enum + M4's `ScanEventsConsumer` switch + needs M7/M9 alignment on who owns each transition.
2. **New `location_type`: `SHUTTLE`** (hub↔airport vehicle, distinct from the DA-facing `VAN`).
3. **`bag_id` column** (folded into `V8_1`, since the migration was still undeployed) — ties the N fanned-out per-parcel rows of a bag scan together. Bag scans set it; parcel/van scans leave it NULL.
4. **Delivery is scan + OTP** — the `DELIVERED` scan confirms "right parcel", the OTP confirms "right customer"; both gate `DROPPED`.

The **`bag_id` column + `SHUTTLE` length are already in `V8_1`** (folded into PR1's migration — it was undeployed). Still ahead as **PR3 / shared-contract** work: the 3 new `ScanEventType` values + M4 consumer branches, and the M7-driven fan-out seam.
