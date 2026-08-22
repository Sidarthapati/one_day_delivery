# Godspeed vs Amazon Logistics — Gap Analysis

**Context.** We captured a ~51-min walkthrough of Amazon's **logistics operations back-office** (station
LKOF/Lucknow) — the Route-Execution control tower, the Station Command Center (SCC), the "amazon cloak"
loss-recovery tool, and a per-parcel Excel data export. This document maps each Amazon capability to our
Godspeed / one_day_delivery platform and scores the delta. Verdicts: **✅ Have · 🟡 Partial · 🔴 Missing.**

> The video shows almost none of Amazon's *driver mobile app* — it's the **ops/station/finance** layer and
> the **data model**. So most gaps are on our **web consoles (Station/Admin/Hub) and backend analytics**, and
> our driver app (`oneday-driver-app`) actually compares *favourably* on on-road execution primitives
> (scan, OTP, GPS, van loops) that Amazon didn't show.

Our modules referenced: M3 grid, M4 orders, M5 dispatch, M6 routing, M7 hub, M8 barcode, M9 airline,
M10 SLA, M11 exceptions (⚠️ **empty shell**), M12 shuttle; consoles = customer/business/hub/station/airline/admin.

---

## Scorecard summary

| # | Capability area | Amazon | Godspeed today | Verdict |
|---|---|---|---|---|
| A | Live route-execution control tower (DA pace, RTS, risk) | Rich | SLA lanes + van map + state buckets | 🟡 Partial |
| B | GPS driver-trace **replay** (time-scrubber) + heatmap | Yes | GPS captured, live map only | 🟡 Partial |
| C | Per-package **event/scan history** timeline (ops view) | Rich | Strong ledger, thin ops UI | 🟡 Partial |
| D | Package **search + filters + CSV bulk export + deep-links** | Yes | Basic list filters, no export | 🔴 Missing (export/deep-link) |
| E | **Ageing / dwell** analytics (days-in-status buckets) | Yes | SLA deadlines, no ageing report | 🟡 Partial |
| F | **Exceptions / problem-solve / RTO** engine | Rich | **M11 empty shell** | 🔴 Missing |
| G | **Reschedule / reassign station / on-road override / batch ops** | Yes | Enum states, no orchestration | 🔴 Missing |
| H | Parcel **disposition rollups** (reattemptable/undeliverable/missing/RTS) | Yes | Failed/RTO states only | 🟡 Partial |
| I | **COD driver cash reconciliation** (collected/deposited/variance) | Yes | Admin COD cash recon | ✅ Have |
| J | **Bank-deposit remittance** w/ expected-vs-actual variance + barcoded slip scan-back | Yes | Remittance + payouts, no variance/slip loop | 🟡 Partial |
| K | **Station layout** config (induct tables + printable QR, sort zones, induct/stow) | Yes | Hub stands/zones/bags | 🟡 Partial |
| L | **Outbound depart / print-depart** manifests | Yes | Hub bag manifests + AWB | 🟡 Partial |
| M | **Shift / scheduling** planning (work blocks, configure plan) | Yes | Roster + shift jobs, no planner UI | 🟡 Partial |
| N | **DA performance scorecards** (stops/hr, on-time, datewise) | Yes | **None** | 🔴 Missing |
| O | **DA/DSP payments & earnings** | Yes (Payments nav) | None (payout = COD only) | 🔴 Missing |
| P | **Partner loss-recovery / dispute (chargeback)** tool | Dedicated (cloak) | **None** | 🔴 Missing |
| Q | **Fraud/integrity** (fake-GPS, bad-scan, deductions) | Tracked | Append-only ledger only | 🔴 Missing |
| R | Per-parcel **data-model richness** (ship class, sort zone/aisle, dwell, RDD) | ~40+ fields | Solid core, thin sortation/service-type | 🟡 Partial |
| S | **Multi-DSP** model (vendor drivers + store pickup-points) | Yes | Own DAs + B2B accounts | 🟡 Partial |
| T | Auth / SSO | Cognito "Cloak" + WorkSpaces | M1 JWT + Google + phone OTP | ✅ Have |
| U | **On-road driver app** (scan / OTP / van loops / GPS) | *not shown* | Native RN app, full flows | ✅ Have (our strength) |

**Net:** 3 ✅ · 11 🟡 · 7 🔴. The deltas cluster in **exceptions/problem-solve (F,G,H)**,
**analytics/reporting (D,E,N)**, **partner finance & recovery (O,P)**, and **fraud/integrity (Q)**.

---

## Detailed gaps (by theme)

### 1. Live execution control tower  (A,B,H — 🟡)
Amazon's Route-Execution screen is a single pane showing, per station: in-progress/inactive/behind/at-risk
DA counts, attempt-success %, and per-DA **pace (avg vs last-hour stops/hr)** + **projected Return-To-Station**
+ **"next stop expected N hours ago"**, over a live map with **GPS trace replay (time-scrubber) and heatmap**.
- **We have:** M10 SLA control tower (GREEN/AMBER/RED lanes, red-queue, triage priority), `AdminOrderSummaryService`
  parcel-state bucket counts, M6 `van_live_status` live map (`GET /routing/{cityId}/live`) with running-late
  detection, `da_status`/`da_gps_ping` breadcrumbs, dispatch `expected_eta`.
- **Missing:** per-DA **stops/hr pace**, **projected-RTS**, **attempt-success %**, execution-progress gauges,
  and **historical GPS-trace replay with a time-scrubber + heatmap** (we store the breadcrumb but have no replay UI).
- **Effort:** medium — data mostly exists (`da_gps_ping`, dispatch queue, scan ledger); this is largely an
  **aggregation + Station-console dashboard** build, plus a trace-replay map component.

### 2. Package event history & search  (C,D — 🟡/🔴)
Amazon's SCC gives a **global package search** (rich filters, 1000+ results, **CSV export**, **shareable
detail deep-link**) → per-package **event timeline** (state, operation, actor email, node, driver, GPS lat, reason).
- **We have:** a genuinely strong spine — `scan_ledger` (append-only, DB-trigger enforced, actor/counterparty/
  location/client-scan-id), `shipment_state_history`, custody + handoff reconciliation, customer LiveTrack,
  hub ParcelLocator, station orders list.
- **Missing:** a **unified ops "search → full event timeline"** view that surfaces the ledger with actor + GPS;
  **CSV/Excel bulk export** anywhere in the consoles; **shareable per-package deep-links** for ops.
- **Effort:** low–medium — mostly UI over existing data + an export endpoint.

### 3. Exceptions / problem-solve / RTO  (F,G,H — 🔴 biggest gap)
Amazon: **Exceptions View + Manage Packages** (reschedule delivery window, **change assigned station**,
**on-road management/override**, **batch tracking-ID operations**), a reason-code taxonomy
(Customer Unavailable, Out of Delivery Time, NOT_REQUIRED, Delivery Rejected, Drop failed), and disposition
rollups (reattemptable / undeliverable / **missing** / returned-to-station / SWA loose pickup).
- **We have:** state-machine states for `PICKUP_FAILED`/`DELIVERY_FAILED`/`RTO_*`/reschedule/hub-return; a
  station `/exceptions` page (UI); an `ExceptionsEventsConsumer` **stub**.
- **Missing / broken:** **M11 `exceptions` module is an empty shell** — *nothing emits* `ExceptionsEvent`, so
  there is no RTO/reschedule engine, **no structured reason-code taxonomy** (failures are free-text), no
  attempt-count/max-attempt policy, no call-center triage queue, no "missing" disposition, no batch ops, no
  change-station / on-road override.
- **Effort:** **high** — this is a whole module to build (producer + reason taxonomy + attempt policy +
  problem-solve console + batch tooling). Highest-leverage gap to close.

### 4. COD / cash  (I,J — ✅/🟡)
- **✅ Driver cash reconciliation:** we have `DaCodController` deposits + `cod_cash_deposit` reconciled by admin
  (COD cash-recon UI), plus B2B `cod_remittance` (gross/fee/net/UTR) and bank penny-drop — good parity with
  Amazon's Driver Reconciliation.
- **🟡 Bank deposits:** Amazon adds an **expected-vs-actual cash variance** workflow (Create Remittance /
  Prepare Deposit / variance reason) reconciled against **physical barcoded CMS deposit slips** (Radiant)
  scanned back. We have remittance + payout but **no expected-vs-actual variance loop and no barcoded
  deposit-slip scan-back / cash-management-services integration**.
- **Effort:** medium.

### 5. Station / hub physical ops  (K,L — 🟡)
Amazon SCC: **Station Layout** (Induct Tables with **per-area printable QR codes**, Small/Volumetric/
Exceptions/High-Density zones), **Induct/Stow** associate flows, **Outbound Depart / PrintDepart** manifests.
- **We have:** M7 hub **stands** (dynamic allocation, zones, capacity, reassignment audit), inbound dock/receipt,
  flight & delivery bags + manifests, hub console, `hub_load_snapshot` overload alerts.
- **Missing:** induct-table **QR-code labeling/printing**, **induct/stow** associate scan flows, and a physical
  **station-layout config UI**; explicit print-depart paperwork.
- **Effort:** medium.

### 6. Workforce: scheduling + performance + pay  (M,N,O — 🟡/🔴)
- **🟡 Scheduling:** Amazon has a **Scheduling** tab + **"Configure Plan" shift planning** + work-blocks/time-left-
  on-block. We have `da_profile.shift`, `da_status`, `ShiftLoadJob`/`ShiftEndJob`, grid roster (G1 shipped) — but
  **no shift-planning UI, no work-blocks, no clock-in/out or leave**.
- **🔴 Performance:** Amazon exposes **DA scorecards** (stops/hr, on-time %, datewise performance, "Daily
  Performance"/"Score Card" files). We have **none** — no per-DA performance metrics, ratings, or scorecards.
- **🔴 Payments:** Amazon has a **Payments** nav (DA/DSP settlement). We have **no DA earnings/compensation**
  ledger ("payout" today = COD remittance + DA cash *deposit* reconciliation, not driver pay).
- **Effort:** performance = medium (derive from ledger + dispatch); pay = high (new domain); scheduling UI = medium.

### 7. Partner loss-recovery / dispute  (P — 🔴 entirely absent)
Amazon "cloak": a dedicated **chargeback/loss-recovery** back-office — per-parcel **₹ loss value**, loss
buckets/sub-buckets, recoverable classification, case lifecycle, **SLA-bound dispute workflow**, bulk dispute/export.
- **We have:** nothing comparable.
- **Relevance:** high for us — we run **B2B + a DSP-like own-fleet** model, so partner accountability, loss
  attribution and disputes will matter at scale.
- **Effort:** high — a new module.

### 8. Fraud / integrity  (Q — 🔴)
Amazon (at least) tracks **fake-GPS** (spoof) files, **bad-scan** analytics, and **NLSU deductions**.
- **We have:** append-only `scan_ledger` (tamper-resistant) — a good foundation — but **no GPS-spoof detection,
  no bad-scan analytics, no deduction workflow**.
- **Effort:** medium (analytics over existing GPS + scan data).

### 9. Data model richness  (R,S — 🟡)
Amazon's per-parcel export carries ~40+ fields. Mapping to us:
- **✅ We already have:** weight, L/W/H + volumetric + chargeable weight, payment mode (PREPAID/COD), COD
  amount, origin/dest tile, SLA commitment minutes, ETA promised/updated, assigned flight, rate-card version,
  order value, postal/city, state, route/manifest linkage.
- **🟡 Thinner / missing:** **ship-method speed class** (AIR/AFTERNOON/STANDARD) and **ship-option cutoff class**
  (next-in/exp-in/SUN-close…) as first-class fields; **sort zone / cluster / aisle / sort-location** granularity;
  **service-type taxonomy** (ES/SWA/SF); **dwell "minutes-in-last-scan"** as a tracked metric; **promised-vs-
  estimated-vs-RDD** as distinct dates; **merchant/store attribution**; **exchange flag**; **multi-DSP vendor**
  dimension (Amazon reconciles many DSP companies + store pickup-points; we model own DAs + B2B accounts).
- **Effort:** low–medium (mostly additive columns + derivation).

### 10. Reporting / export  (D — 🔴)
Amazon exports **CSV/Excel everywhere** (bulk-download of 1000s of rows) feeding offline scorecards/audits.
- **We have:** essentially **no console CSV/Excel export**.
- **Effort:** low — generic export endpoints per console list.

---

## Where we are *ahead* (or at parity)
- **On-road driver app (U):** our native `oneday-driver-app` (DA pickup/delivery + failure sheets, van
  loop/load/stops/return-scan, shuttle, barcode camera scan, background GPS, native-maps nav, OTP POD) is a
  real product; Amazon's *driver* app wasn't in the video to compare, but our on-road execution primitives are solid.
- **Append-only integrity (T,Q-foundation):** DB-trigger-enforced immutable `scan_ledger` is a strong base.
- **Multi-leg intercity + airline (M9) + hub bags + shuttle (M12):** our air-leg model is arguably richer than
  a single-station last-mile view.
- **Customer/B2B self-serve:** customer web + B2B console (wallet, dev keys/webhooks, white-label tracking,
  bulk upload, invoices, remittances) — a shipper-facing surface Amazon's ops video doesn't cover.

---

## Recommended priority order (highest leverage first)
1. **Build M11 exceptions/problem-solve engine** (F,G,H) — reason-code taxonomy, attempt policy, RTO producer,
   reschedule/reassign/on-road override, batch ops, disposition rollups. *Unblocks the single biggest delta.*
2. **Ops analytics layer** (A,B,N,D,E) — per-DA pace/RTS/attempt-success dashboard, GPS-trace replay, ageing
   report, DA scorecards, and **CSV export** across consoles. *Mostly aggregation over data we already store.*
3. **Cash variance + deposit scan-back** (J) and **DA/DSP payments** (O).
4. **Partner loss-recovery/dispute module** (P) and **fraud/integrity analytics** (Q).
5. **Data-model enrichment** (R) — ship-class, sort granularity, dwell, service-type, RDD split — folded into 1–2.
