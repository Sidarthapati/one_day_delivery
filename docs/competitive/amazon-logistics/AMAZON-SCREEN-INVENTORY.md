# Amazon Logistics — Screen & Capability Inventory

Derived from the 137-screen collection in `screens/` (see `README.md`). **PII redacted:** the raw
frames contain real driver/customer names, phone numbers, actor emails, tracking IDs and cash
figures — those stay in the local images only. Below we catalog **screens, capabilities and data
*schema*** using generic/representative values.

> **Key finding.** The video is **not** the Amazon driver/Flex mobile app. It is a Google-Meet
> screen-share walkthrough (station **LKOF – Lucknow**, 2026-08-21) of Amazon's **web operations
> back-office**, an Excel data export, and a partner-finance tool. So the right comparison is
> against our **Station / Admin / Hub consoles + dispatch/routing/SLA backend + data model**, not
> primarily our driver app. This is exactly the "ton of data stored" the request referred to.

The four Amazon surfaces shown:

---

## 1. Route Execution / Itineraries console
`logistics.amazon.in/operations/execution/...` — the station **control tower** for live DA/route monitoring.

- **Top nav:** Home · Scheduling · Work Summary Tool · Operations · Performance · Payments · Setup · Support.
- **Fleet KPI header:** DA/DP status (Total, In progress, Inactive); activity (On break, No breaks taken,
  Late departing, Not departed, **Unknown stops**); **Risk (Ahead / At risk / Behind)**; Work-hour risk;
  **Rescue Actions**; Multi-transporter count; **Pickup failed** count.
- **Execution gauges:** Locations % · Stop % · Parcel % · **Attempt Success %** (e.g. 96–97%).
- **Package status rollup:** Remaining · Reattemptable · Undeliverable · **Missing** · **Returned to station** ·
  Pending containers/packages pickup.
- **Customer returns:** Total / Remaining / Complete.
- **Routes list & DAs list** (toggle): filter by name/ID/scan; **sort by** Progress, Departure/Break status,
  Route code, Name, Time left on shift, **Projected RTS (earliest/latest)**, **EV charge**.
- **Per-DA card:** transporter ID, route IDs, phone, VIN, **time left on block**, work block, **planned vs
  projected Return-To-Station (RTS)** (can spill to next day when behind), breaks taken, live status,
  **"Next stop #N (expected N hours ago)"**, **pace = avg stops/hr vs last-hour stops/hr**, deliveries vs
  stops, pickups vs deliveries split.
- **Per-route progress drill-down:** locations/stops/deliveries/pickups done vs total, **multi-location stops**,
  parcel disposition (delivered / reattemptable / undeliverable / missing / returned-to-station / **SWA loose
  pickup**), **"X stops / Y packages behind planned time"**, "expected to complete within planned time".
- **Live map:** Satellite · **Driver Trace (GPS breadcrumb)** · **Heatmap** · **full-shift time-scrubber replay**;
  clustered stop pins, DA-initial markers, red at-risk markers.
- **Actions:** Edit Route Assignments · **Download CSV** · Cortex FAQs · per-DA event/activity timeline.

## 2. Station Command Center (SCC)
`amazonlogistics.eu/station/dashboard/...` — station operations. Dark-mode, station selector, global package search.

- **Package Summary**
  - **Dwell** · **Ageing** (packages >1 day per status; **Forward/Reverse leg**; <1/1/2/3-day buckets; data-freshness timestamp).
  - **RTS** (return-to-station).
  - **Search** — rich filters (Cycle, Estimated Arrival Date, Station Arrival Time, **Shipment Type**:
    Core Volume / Ship With Amazon / Undeliverables / Customer Return; **Package Status** multi-select; date ranges).
    Results 1000+ with **Export to CSV**; sortable columns; **shareable per-package detail deep-link**.
- **Package detail = event/scan history timeline** — per row: timestamp, state, **operation** (Package Scan,
  Driver Assignment, Associate Debrief, Transport Request Update, Marked For Reprocess), address, **sub-reason**
  (Customer Unavailable, Out of Delivery Time), **actor email**, **node** (Middle Mile Node / FADE region),
  **driver name**, **GPS latitude**, scheduled delivery. Full append-only lifecycle (inducted → in transit →
  driver assignment → delivery failed → rescheduled).
- **Problem Solve** — **Exceptions View** · **Manage Packages** (sub-tabs: Manage schedule / Change assigned
  station / On-road management; **batch** tracking-ID ops; reschedule delivery windows; shipment types:
  Delivery / Return pickup / Free replace pickup / Exchange pickup / MFN pickup).
- **Outbound** — Depart · **PrintDepart** (departure manifests).
- **Cash** — **Overview** · **Driver Reconciliation** (per-driver collected vs deposited vs **variance**, covering
  both DSP drivers and store/pickup-point partners) · **Bank Deposits** (remittances: **Expected / Actual /
  Variance + reason**, Create Remittance, **Prepare Deposit**, Excel export; reconciled against physical
  **barcoded CMS deposit slips** — e.g. Radiant Cash Management — scanned back for reconciliation).
- **Associate** — **Induct** · **Stow**.
- **Station Management** — **Station Layout** (Induct Tables with **per-area printable QR codes** [ASL1…ASL19],
  Small, Volumetric, Exceptions, High Density Points) · Station · Employee.
- **Resource Management · Feature Management · Anisa · shift-plan config ("Configure Plan")**.

## 3. "amazon cloak" — partner loss-recovery / dispute (chargeback) tool
`cloak.tech.amazon.dev` — DSP-facing finance/dispute back-office.

- **Potential Recovery Bucket** (e.g. ~1,498 items) — per-parcel **₹ loss value**.
- Per-case: **Loss Bucket / Loss Sub-Bucket** (e.g. "WRTS but MDR", "RTS fail and MDR"), **NL Status**
  (e.g. "Recoverable – Partner miss"), **Case Status** (In Progress…), Impact Date, **SLA** (action pending by
  eDSP till date), Action Owner (eDSP), Partner Name/ShortCode, Zone, Latest Scan Date.
- **Dispute Window** (disputed-by user + timestamp), **Bulk Dispute**, **Bulk Export**, Apply Filters.

## 4. DSP package data export (Excel `PackageResults`) — the per-parcel data model
The "ton of data": each parcel row carries ~40+ fields —

`Tracking ID · Order ID (3-7-7) · SAL Color · Last Updated · Source (feeder facility) · State · Destination ·
Reason · Station · Operation · Route Code · Last Scan (+by whom) · Address Type · Ship Option (cutoff class:
next-in-grd / exp-in-grd / SUN-close / SAT-close / Std) · Ship Method (ATS_AIR / ATS_AFTERNOON / ATS_STANDARD) ·
Delivery · Cluster · Aisle · Dispatch time · DSP Name · Assigned/Inducted counts · Minutes-in-Last-Scan (dwell) ·
Payment (PREPAY / CASH=COD) · District · Ship Date · Scheduled · Promised Delivery Date · Sort Zone · Warehouse
(origin FC) · Manifest Route · Order Amount · Driver ID · Package L/W/H (cm) · Package Weight (kg) · Estimated
Arrival Date · RDD · Route Seq / Route Sort · Receivable (COD amount) · Holder Name / Service Type (ES/SWA/SF) ·
Province · City · Postal · Consignee (tokenized) · Exchange flag · Actual Pay · Store Name · Merchant ·
Source Address (hashed) · Package Category · Sort Location`

Adjacent desktop files reveal further back-office artifacts: **"Fake GPS" tracker** (GPS-spoofing/fraud watch),
**bad-scan tracking**, **daily/date-wise performance scorecards**, **cash audit sheets**, **NLSU deductions**.

## 5. Auth
Amazon **"Cloak" Cognito SSO** (corporate ID or email/password) + **Amazon WorkSpaces** gate all consoles.

---

### Surface → our-platform analog (quick map)
| Amazon surface | Closest Godspeed analog |
|---|---|
| Route Execution control tower | Station console (SLA control tower, dispatch board) + M5 dispatch + M6 live map + M10 SLA |
| Station Command Center | Hub console (M7) + Station console + Admin console |
| Cash (Driver Recon / Bank Deposits) | Admin COD cash recon + payouts (M4) + B2B remittances |
| amazon cloak (loss recovery/dispute) | **— none —** |
| PackageResults data model | Shipment + scan_ledger + SLA + grid tiles data model (M4/M8/M10/M3) |
