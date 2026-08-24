# Godspeed — CEO Feature Set: Current State + Sid/Agniva Task Split

## Context

For the upcoming CEO discussion we have 20 required features. This document does two things:
1. **Where we are today** — for each of the 20, what already exists in the code (with anchors), what's missing, and rough effort.
2. **A two-person split** (Sid + Agniva) targeting **Thursday**, covering the full stack per feature — backend (this repo), business/admin/station **web consoles** (`oneday-web`), and the **native Android driver app**. Everything is in scope; nothing is deferred unless blocked by the legal formation of the Godspeed entity.

Sid keeps **#10 (order repair)** and **#16 (mid-day DA reassignment)** — both "nearly done". The remaining 18 are split along a clean seam: **Sid owns Operations / DA / Field / Station; Agniva owns Merchant / Customer / Support / Platform.** The split is by *effort*, not count — Sid's WIP is nearly finished, so he absorbs the two heaviest ops greenfields (#19, #20).

---

## Current-state snapshot (all 20)

Legend: 🟢 built · 🟡 partial (foundation exists) · 🔴 not built · **Effort**: S / M / L (Done = verify/polish)

| # | Feature | State | Key anchors / notes | Effort |
|---|---------|-------|---------------------|--------|
| 1 | Section-based categories per merchant | 🔴 | No category/tag field on `Shipment`; `B2bAccount` has no category config. New table + per-shipment field + merchant CRUD. | M |
| 2 | Saved warehouses | 🟢 | Exists (B2B portal saved addresses / warehouses). | Done |
| 3 | Wallet (rechargeable) | 🟢 | `WalletService`/`wallet_transaction`/`V4_28`; prepaid balance on `b2b_accounts`. | Done |
| 4 | Multiple service accounts / merchant | 🔴 | Only `B2bAccount.ownerUserId` (1:1). RBAC primitives exist (`Role`/`Permission`/`RoleController`) but no membership/invite/sub-account. | L |
| 5 | Merchant analytics dashboard | 🟡 | Analytics exist but **ADMIN/ops-scoped** (`AdminOrderSummaryService`, ageing, dispatch metrics). None keyed on `b2b_account_id`. `eta_promised` + analytics indexes present. | M–L |
| 6 | Tracking & reports (merchant) | 🟡 | Tracking done + merchant-authz'd (`MyShipmentsController`, `TrackingController /mine/{ref}/track`, webhooks, white-label). **CSV export is ADMIN-only** → needs a merchant-scoped export. | S–M |
| 7 | Merchant alerts (wallet low, card expiry, new-booking email…) | 🟡 | Notifier infra exists (`Notifier`/`NotifierImpl`, `SendGridEmailSender`, `Msg91SmsSender`, `NotificationDispatcher`) but **env-gated/untested** + no wallet-threshold / new-booking / card-expiry triggers. | M |
| 8 | Hub naming → "Delhi Hub"/"Mahipalpur Hub" | 🟡 | No Hub master entity; `originHub`/`destHub` are free-form city strings on `FlightBag`, used as **query keys** + in events. Display relabel is small; real hub identities (sub-city, multi-hub-per-city) is medium. | M |
| 9 | Order → N Shipments drill-down (1:n) | 🟢 | **Freshly merged + tested** (`V4_40 parcel_orders`, `ParcelOrder`, `OrderRefService`, `order_id` on shipments, `/orders/mine`, `/admin/orders`, `OrderStatusReducer`). Residual: **driver-app/dispatch grouping by (order, location)** — needs `ShipmentCreatedEvent` change + dispatch migration. | Done + S residual |
| 10 | Order repair — add/remove a parcel (ops + wallet rebalance) | 🟡 **(SID, WIP)** | Only increment rollup (`ParcelOrderRepository.addShipment`); **no decrement / remove / partial refund**. Reuse `PaymentPort.initiateRefund` + `WalletService.refundForCancellation`. | L (WIP) |
| 11 | Driver ETA to customer / pickup slots + delay mail | 🟡 | Slots built (`PickupSlots`, `scheduled_pickup_*`, `V4_34`); ETA built (`EtaPort`, `eta_promised`, `minutesLate` in track). **Missing:** slot-capacity caps + proactive "ETA slipped → accept/cancel" customer mail. | S–M |
| 12 | DA availability accept/reject (Yes/No, clickable) | 🔴 | Assignment is fully automatic; no `OFFERED/ACCEPTED` state, no `/accept` `/reject`, no timeout→reassign, no driver push. | M |
| 13 | Call center / support ticket ("call me") | 🟡 | M11 has ops-only `ExceptionCase` (always shipment-scoped) + `CALL_CENTER_AGENT` role + queue console. **No customer/merchant-initiated ticket / callback / shipment-optional case.** | M |
| 14 | Interaction log / chat + Jira (auto-assign, 1h SLA, escalate) | 🔴 | No chat/message/thread table; only `ExceptionCase.notes` + `ExceptionAction` audit. **Zero Jira code.** SLA-escalation concept exists for parcel legs, not tickets. | L |
| 15 | DA onboarding + geocoded attendance | 🟡 | Onboarding done (`DaController`, `DaRegistrationService`, `da_profile`). Attendance is only implicit (GPS heartbeat + `AbsentDaDetectionJob`). **Missing:** explicit geocoded present/absent check-in + admin muster. | S–M |
| 16 | Pause / reassign DA mid-shift (reassignment algo) | 🟡 **(SID, WIP)** | Design in `docs/escalation/ORDER-ABSTRACTION-AND-MIDDAY-DA-REASSIGNMENT.md`. ~70% orchestration over existing parts (`DA_ABSENT`, `gridDisk`, `ProposalService` override, `ContiguityValidator`). Needs no-show trigger + `DA_TO_DA` custody handoff. | L (WIP) |
| 17 | Tasks & tickets (pickup, delivery) | 🟢 | Comprehensive (`dispatch_queue`, `DaTaskService`, full lifecycle + OTP). | Done |
| 18 | DA Wallet (COD reconciliation) | 🟡 | Recon ledger exists (`cod_cash_deposit`, `V4_33`, `DaCodController`, `AdminCodController`). **Missing:** per-DA running balance / statement / payout (a true wallet). | S–M |
| 19 | Asset registering (van, barcode, scanner) | 🔴 | No fleet/asset registry — `vanId` is a synthetic `(cityId, index)` UUID; fleet = a count (`city_fleet_config.vans_available`). Barcode = parcel-ID gen, not device registry. | L |
| 20 | Station Manager (custodian: cash, inventory, load/OTW, staff, temp onboarding) | 🟡 | `STATION_MANAGER` role + control-tower views exist (`StationDispatchController`, scorecards, hub load). **Missing:** station cash custody, inventory, consolidated load/OTW, staff roster, temp-staff onboarding, custodian entity. | L |

**Foundational dependency (call out to CEO):** a **real notification service** (email/SMS/push, templated, retried) underpins #7, #11, #12, #14 and the #16 escalation output. The scaffolding is present (`Notifier` + SendGrid/Msg91 senders + `NotificationDispatcher`, webhook dispatch/retry/signature pattern in `WebhookServiceImpl`); it needs productionizing + new trigger events. **Agniva owns this foundation and lands it early Day 1** so Sid's DA-push features can consume it.

---

## The split

### SID — Operations / DA / Field / Station
Domain: `orders` (repair), `dispatch`, `grid`, `routing`, `barcode`, `hub`, `auth` (DA); **admin + station web consoles**; **driver app**.

| # | Feature | Backend | Web console | Driver app |
|---|---------|---------|-------------|------------|
| 10 | Order repair (add/remove parcel) — **WIP** | `OrderService` mutate + decrement rollup; partial refund (`PaymentPort`) / wallet rebalance (`WalletService.refundForCancellation`); idempotency; capacity/manifest recompute | Admin/station "adjust order" action | Reflect adjusted parcel count on the pickup stop |
| 16 | Mid-day DA reassignment — **WIP** | `DaReassignmentService` (grid) even-split + auto-apply override; `DA_TO_DA` scan; dispatch territory-refresh seam; roster-vs-online no-show trigger | Station "DA unavailable / override" | Rendezvous / DA→DA handoff scan screen |
| 12 | DA accept/reject | `OFFERED/ACCEPTED` state + `/accept` `/reject` + timeout→reassign + events | Station visibility of pending offers | Push + Yes/No accept card |
| 15 | DA onboarding + geocoded attendance | Geocoded check-in record + admin muster (onboarding already done) | Admin muster / present-absent view | "Mark present" geocoded check-in |
| 18 | DA Wallet (COD) | Per-DA running ledger + statement over `cod_cash_deposit` backbone | Admin recon (exists — extend) | DA wallet/statement screen |
| 19 | Asset registry | New asset domain (vehicles w/ plate/VIN/capacity, scanners); bind real assets → synthetic van slots | Admin asset CRUD | (scanner/device binding if needed) |
| 20 | Station Manager custodian | Station cash-custody + inventory + consolidated load/OTW + staff roster + temp-staff onboarding entity | **Station console build-out** | — |
| 8 | Hub naming | Hub master / display-name mapping over `originHub`/`destHub` keys | Relabel across hub/station views | — |
| 9 | Driver-app order grouping (residual) | `ShipmentCreatedEvent` order_id + `dispatch_queue.order_id`; group tasks by (order, location) | — | Stops grouped by order+location |

### AGNIVA — Merchant / Customer / Support / Platform
Domain: `orders` (merchant read/alerts), `auth` (multi-account), `pricing`, `common` (notifications), `exceptions` (support); **business + customer web consoles**; customer notifications.

| # | Feature | Backend | Web console | Customer/merchant surface |
|---|---------|---------|-------------|---------------------------|
| — | **Notification service (foundation, Day 1)** | Productionize `Notifier` + SendGrid/Msg91; templating + retry; new `NotificationEventType`s | — | Email/SMS delivery for all consumers |
| 1 | Section categories per merchant | Category-config table + per-shipment field + CRUD; filter/report plumbing | Business "categories" mgmt + shipment tagging | Category on booking + lists |
| 4 | Multiple service accounts | Membership join table + invite flow + account-scoped authz (reuse RBAC primitives) | Business "team & roles" | Invite / sub-user login |
| 5 | Merchant analytics dashboard | Aggregations keyed on `b2b_account_id` (success %, on-time via `eta_promised`, GMV, city split) | Business analytics dashboard | — |
| 6 | Tracking & reports (merchant) | Merchant-scoped CSV export (reuse `toCsv`, owner-filtered) | Business reports/exports | Merchant tracking (mostly done) |
| 7 | Merchant alerts | Wallet-threshold check on debit, new-booking hook, card-expiry watcher → notifications | Alert preferences | Email/SMS to merchant |
| 11 | Pickup slots + delay mail | Slot-capacity caps; "ETA slipped → accept/cancel" mail on delay | Slot picker polish | Customer delay email w/ accept/cancel |
| 13 | Call center / support ticket | Shipment-optional ticket + "call me"/callback intake (reuse M11 queue/console/role) | Support queue console | Customer/merchant "contact support" |
| 14 | Interaction log / chat + Jira | Chat/thread model; Jira client (auto-create/assign, 1h SLA, escalate) | Chat UI in support console | Two-way chat thread |
| 2 / 3 / 17 | Verify built features | Smoke-test warehouses, wallet, tasks | — | — |

**Rough load:** Sid ≈ two big greenfields (#19, #20) + WIP finish + DA cluster; Agniva ≈ notif foundation + merchant cluster + support cluster (three L: #4, #14, +analytics). Balanced given Sid's #10/#16 are nearly done. If Thursday is tight, the natural MVP de-scopes are **#19 → vehicle-only registry** and **#20 → cash + load/OTW first, staff/temp-onboarding later**, and **#14 → chat-only, Jira as fast-follow**.

---

## Dependencies & coordination
- **Notification service (Agniva, Day 1)** blocks #7, #11, #12-push, #14, #16-escalation. Agniva ships the sender + a `send(event)` seam first; Sid consumes it for DA push.
- **#15 attendance/roster** feeds **#16 no-show detection** (both Sid — clean, no cross-owner dependency).
- **#9 driver grouping** builds on the merged order abstraction (#9/#10, Sid's domain).
- **#1 categories / #5 analytics / #6 reports** share the merchant shipment read-model (all Agniva — clean).
- Cross-repo: each feature spans backend + `oneday-web` console + driver app; keep the same owner across all three layers of a feature to avoid contract drift.

## Verification (per feature, end-to-end)
- Build/run on **JDK 21** (`JAVA_HOME=/opt/homebrew/opt/openjdk@21`); `mvn test -pl <module>` against local Postgres.
- **Sid:** repair e2e (place 10, remove 1 → parcel_count↓, wallet credited); mark DA ABSENT mid-shift → territory override + queued re-created + `DA_TO_DA` handoff reconciles; DA reject → auto-reassign; geocoded check-in appears in muster; register a van → bound to route slot; station console shows cash/load.
- **Agniva:** send test email/SMS via wired provider; create category → tag shipment → appears in filter/report; invite sub-user → account-scoped login; merchant dashboard numbers reconcile vs raw shipments; low-wallet debit → alert fires; delayed shipment → customer accept/cancel mail; customer opens ticket → chat thread + Jira issue created.
- Demo target: each feature walkable in its console (`oneday-web` apps: business / customer / admin / station) and, where relevant, the driver app.

## Deliverable
On approval, this doc gets saved to `docs/escalation/CEO-FEATURE-SPLIT.md` (alongside the existing escalation docs) for sharing with the CEO / Agniva.
