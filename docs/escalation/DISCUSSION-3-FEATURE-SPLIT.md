# Godspeed — Discussion 3: Pending Features, Current State + Sid/Agniva Split

## Context

The third CEO discussion produced two handwritten pages of items to cover (11 in all). This document
does the same two things Discussions 1 and 2 did:

1. **Where we are today** — for each item, what already exists in the code (with file anchors), what's
   missing, and rough effort.
2. **A two-person split** along the same seam as before — **Sid owns Operations / DA / Field /
   Station; Agniva owns Merchant / Customer / Support / Platform** — full stack per feature (backend
   in this repo, the `oneday-web` consoles, and the native `oneday-driver-app`).

**Three items are joint Sid + Agniva builds.** **(vii) Auxiliary mode** and **(viii) Disposition
states** are one shared DA-availability build (large, cross-cutting, and nothing like it exists yet);
**(x) Global ETA** is a second joint build (a real ETA engine plus the notification trigger). These are
the headline collaborative work of this round — reviewed and designed together, then built across the
seam.

**One item is not an engineering task.** **(ii) Bill for pickup / cancellation charges** is a business
discussion point — the business will decide whether and how we charge; it is recorded here for context
but is **unassigned** and blocks no build.

Current state below was verified against the live code (backend `orders`/`exceptions`/`dispatch`/
`grid`/`routing`/`pricing`/`assets`, the `oneday-web` apps, and `oneday-driver-app`) on branch
`feat/delivery-outcomes`. Cross-references to the earlier discussions are noted where they overlap.

Legend: 🟢 built · 🟡 partial (foundation exists) · 🔴 not built · **Effort**: S / M / L.

---

## Current-state snapshot

### Page 2 (items i–vii)

| # | Feature (as discussed) | State | Key anchors / what's missing | Effort | Owner |
|---|---|---|---|---|---|
| i | **Backend entity: "Stub"** — a queryable / billable parent **above** the Order (the backend counterpart deferred in Discussion 2, where only the DA-app grouping shipped). | 🔴 (Order layer exists) | The Order layer is shallow and a clean template to clone one level up: `parcel_orders` (`orders/db/migration/orders/V4_40`), `ParcelOrder`, `OrderRefService` + `OrderServiceImpl.createOrder/addShipment` (denormalised rollup). **Build:** a new `parcel_stubs` table (own ref counter `1DD-STB-…`, `b2b_account_id`, `purchase_order_ref`, count/price rollup) + a nullable `stub_id` on `parcel_orders` (bare-UUID convention, no FK) + a `StubService`/`StubRefService` cloned from Order + read APIs cloned from `AdminOrderGroupsController`/`MyOrdersController`; wire the four order-creation call sites (B2B `B2bBookingServiceImpl`, B2C `BookingServiceImpl`, cart `CartServiceImpl`, bulk `BulkUploadServiceImpl`) to create/join a stub. Ties to **(v)** merchant refs. | M | **Sid** |
| ii | **Bill for pickup charges / cancellation charges** — pricing changes. | 🔴 (fully refunding today) | **Discussion point — NOT an engineering task. Business takes the call** on whether/how we charge. Context only: there is no pickup-charge or cancellation-fee anywhere in the code; cancellation is fully refunding (`CancellationServiceImpl.doCancel` → full Razorpay refund / full B2B credit reversal). If the business decides to charge, the seams are: `PricingEngine.price` breakdown map + `RateCard` columns (mirror `codPctBps`/`codMinPaise`), a fee deduction in `CancellationServiceImpl` keyed off `CancellationPolicyImpl` state, and a wallet/invoice line (reuse the proven COD "retain a fee" pattern). | — | **Unassigned (business)** |
| iii | **RTO in any mid-transit** — trigger / handle a return from any point in transit, not only at the delivery attempt (research & solve). | 🟡 (RTO only at delivery-attempt today) | The single hard blocker is one line: `orders/.../service/TransitionRegistry.java:100` registers **only** `DELIVERY_FAILED → RTO_INITIATED`; no other state can legally enter RTO. RTO is initiated from the exceptions console (`ExceptionController` `POST /exceptions/{id}/resolve`, `INITIATE_RTO → RTO_INITIATED`); the return child `<ref>_R` is minted by `ReturnServiceImpl` (born `AT_ORIGIN_HUB`). **Missing:** (1) a `TransitionRegistryConfigurer` bean allowing `RTO_INITIATED` from mid-transit states (PICKED_UP, AT_ORIGIN_HUB, IN_TAKEOFF_BAG, DEPARTED, LANDED, AT_DEST_HUB…); (2) `ReturnServiceImpl.mintChild` assumes the parcel is already at the origin hub — the child's birth state / re-entry point must derive from the original's **current** location, and the in-flight / on-van physical recall handled; (3) an ops trigger that doesn't require a DA-attempt-opened exception case. | L | **Sid** |
| iv | **App-download offer** shown on the accept/reject "today's delivery" page. | 🔴 | Pure front-end, no backend change. The no-login page is `oneday-web/apps/customer/app/d/[token]/page.tsx` (opaque token, `orders/.../api/PublicDeliveryController.java`). Add a store-link CTA in the outer `Stack` and/or on the ACCEPTED success screen (the ideal "you're all set — now get the app" moment). Store links are new constants (none exist yet). Extend `DeliveryConfirmationView` only if server-side targeting is ever wanted. | S | **Sid** |
| v | **Merchant order IDs must be linkable** — store and search by the merchant's own external order id. | 🔴 | Today the only merchant reference is `purchaseOrderRef` — one per **order** (`B2bBookingRequest.purchaseOrderRef` → `ParcelOrder`), **not indexed, not searchable**; shipments carry no external ref at all. **Build:** an indexed `external_ref` / `merchant_order_ref` (on shipment and/or the new stub), a repository finder + a lookup/search endpoint, and surface it in list/detail DTOs. Shares the merchant-reference design with **(i)** Stub. | S–M | **Sid** |
| vi | **RBAC per user under a B2B account** + per-user budget allocation (members may have different budgets). | 🟡 (coarse OWNER/MEMBER) | `b2b_account_member` (V4_44) carries a role of **OWNER/MEMBER only** and **no budget**; credit and wallet are **account-level** — `B2bBookingServiceImpl` checks `outstandingBalance + total > creditLimit` against the shared account pool, any member draws it. **Build:** a richer per-member role/permission set with enforcement in booking/admin actions, and a per-member `spend_limit_paise` (+ period) checked alongside the account credit check. Extends Discussion-2 (xii) per-user KYC / team membership. | M–L | **Agniva** |
| vii | **Auxiliary mode** — a DA can raise a request that he is temporarily busy with **other company work**; the station manager can see / report / acknowledge it. | 🔴 | **JOINT (DA-availability pair) — see "The joint builds" below.** | L | **Sid + Agniva** |

### Page 1 (items viii–xi)

| # | Feature (as discussed) | State | Key anchors / what's missing | Effort | Owner |
|---|---|---|---|---|---|
| viii | **Disposition states** — Available / lunch-break / on-break; and **for how long a break do we not reassign** the DA's area. | 🔴 | **JOINT (DA-availability pair) — see "The joint builds" below.** | L | **Sid + Agniva** |
| ix | **Complete COD cash settlement — hub-collection → bank-confirmed.** COD is built only *up to hub collection*; the ask is "what about account onwards" — cover the whole downstream until the money **actually enters our bank account**, with confirmation at each cash handoff, and only then remit / refund. | 🟡 (only till hub collection) | Built: per-shipment `cod_collection` (V4_25) marked COLLECTED on delivery; a `cod_cash_deposit` (V4_33, `DaCodController`) that is **self-declared** and admin-reconciled (`AdminCodController`); `cod_remittance` + RazorpayX payout. **Missing the confirmed downstream:** the account-onwards legs (DA → hub → company bank) as tracked, **verified** custody nodes — an OTP / verification at each cash handoff (reuse the `PickupOtp`/`DeliveryOtp` hashed-OTP pattern rather than self-declaration), reconciliation against **actual bank credit**, and a gate so remittance / refund only fire **after funds land in our bank account**. Sid consults on the DA cash-custody leg. | L | **Agniva** |
| x | **Global ETA** — a real ETA in place first, *then* we can build notifications on top. | 🟡 (stub only) | **JOINT — see "The joint builds" below.** `EtaPort`'s only implementation is `app/stubs/StubEtaAdapter` (hardcoded next-day 14:00 / same-day 20:00); M9/airline has no real ETA adapter. The delay-notification path is already built (`ShipmentEtaServiceImpl.reviseEta` → `SHIPMENT_DELAYED` via `NotificationPort`) but only fires on a manual ops revise. | M–L | **Sid + Agniva** |
| xi | **Fleet management software** — **exploratory** (survey the options / build-vs-buy first). | 🟡 (two disconnected pieces) | **Exploratory first: evaluate fleet-management software (off-the-shelf vs. building on M13).** Two disconnected pieces exist today: (1) M13 `assets` already models a vehicle — `AssetCategory.VEHICLE`, `registrationNumber`, custody + maintenance events, plus **reserved-but-unmapped** columns for `metadata` (van capacity/IMEI/insurance/PUC), `warranty_expiry`, `vendor`, cost. (2) Routing has **no `Van` entity** — `vanId` is a bare UUID in `van_manifest` / `van_live_status` / telemetry; "fleet" today is just a per-city **count** (`RoutingFleetController` over `city_fleet_config`). **If we build:** promote the M13 `VEHICLE` asset to the canonical van registry, join it to the routing `vanId`, and layer document/insurance/PUC + maintenance scheduling + driver-vehicle assignment + a fleet console (none exists). | L | **Sid** |

---

## The joint builds

### (vii) Auxiliary mode + (viii) Disposition states — the DA-availability pair

**Nothing like this exists today.** `DaStatusEnum` = `OFFLINE / IDLE / IN_PROGRESS / CRON_LOCKED /
AT_CRON / ABSENT` — there is **no** break, pause, lunch, or auxiliary concept anywhere across the
backend, `oneday-web`, or `oneday-driver-app`. A DA cannot self-signal temporary unavailability at all;
the only "unavailable" path is a manager marking a full **absence**, or the GPS-heartbeat lapse that
flips a DA straight to `ABSENT`.

**Reusable scaffolding (do not rebuild):**
- Midday absence **flood-fill** — `grid/.../service/impl/AbsenceReassignmentPlanner.java` (balanced
  multi-source region-growing that splits a vacated territory across live neighbours, contiguous,
  deterministic).
- **Orchestration + "tasks follow the hex"** — `dispatch/.../service/impl/AbsenceReassignmentServiceImpl.java`
  (preview → apply; orphan hex → `DEFERRED`; in-custody task → `CUSTODY_COLLECT` for the new owner;
  loose task → re-created on the new owner and re-ordered).
- **Preview → approve → auto-apply lifecycle** — `DaAbsenceEvent` (`V5_14`) + `AbsenceAutoApplyJob`.
- **Attendance + geofence** — `AttendanceServiceImpl` (auto-present within the hub geofence, self
  check-in), `da_attendance` (`V5_16`).
- **Consoles & app** — the station `absence` page + attendance muster (`oneday-web/apps/station/.../absence`),
  the driver-app attendance card and the existing "collect from a colleague" (`CUSTODY_COLLECT`) screen.
- **Eventing** — `DaEventType` + M11 alerting.

**Two core missing pieces:**
1. A **duration-tiered trigger** — today `AbsentDaDetectionJob` is binary (heartbeat lapse → `ABSENT`)
   and doesn't itself run the reassignment. We need a break tier *before* the absent flip.
2. A **"pause / retain territory vs. vacate"** branch in the planner's `apply` — today `apply` **always**
   supersedes the DA's assignments and clears the territory. A break that must *hold* the territory has
   no code path; and **territory restore** (giving a returning DA their hexes back) does not exist at all.

**Approach — a shared design-doc phase FIRST.** Sid + Agniva will work through all the scenarios and
produce a design doc before building. This split doc **records the open questions and does not decide
them**:
- The new disposition / auxiliary states and where they live (extend `DaStatusEnum`, or a parallel
  availability dimension so the existing state machine is untouched).
- Short-break behaviour = **pause + hold territory**; the **pause → reassign duration cutoff** — the CEO's
  question of "how long a break before we reassign." *(Undecided — to be worked out with scenarios.)*
- **Auxiliary mode** = a DA self-raises "busy with other company work" → a station-manager approve /
  report loop (a new request entity with approver, reason, and a time-box; a new `DaEventType`). How it
  differs from a break — planned, acknowledged, and possibly tracked against DA utilisation.
- The **return / re-entry problem** — a DA back after the territory was already reassigned (e.g. back
  after 2h from a declared 1h break): auto-restore vs. rejoin as a spare with manager-approved restore
  vs. case-by-case. *(Undecided — to be worked out with scenarios.)*
- Driver-app disposition picker, and station visibility of who is on break / auxiliary / available.

### (x) Global ETA — a real ETA engine, then notifications

A real, system-wide ETA is missing. `EtaPort`'s only implementation is `StubEtaAdapter` (hardcoded
next-day 14:00 / same-city 20:00, `@Profile("!prod")`); M9/airline has no real ETA adapter. ETA is set
only at BOOKED and AT_ORIGIN_HUB (`etaPort.fetchEta` → `setEtaPromised`) and via the manual ops
`reviseEta`. The delay-notification path is **already built** — `ShipmentEtaServiceImpl.reviseEta`
marks the shipment delayed past a grace window and fires `SHIPMENT_DELAYED` through `NotificationPort` —
but it only triggers on a manual revise.

**Joint scope:** a real ETA computation (the routing / airline engine — **Sid** side) plus a proactive
recompute that auto-drives the already-built notification path (customer / notification — **Agniva**
side). The CEO's point is the sequencing: the ETA has to be trustworthy and in place *before* we layer
notifications on it.

---

## The split

Same seam as Discussions 1 and 2. Effort tags carried from the tables above.

### SID — Operations / DA / Field / Station
- **(i) Backend "Stub" entity** — a billable/queryable parent above the Order, cloned from the
  `parcel_orders` pattern. **[M]**
- **(iii) RTO in any mid-transit** — open `RTO_INITIATED` from mid-transit states + derive the return
  child's re-entry point from current location + an ops trigger. **[L]**
- **(iv) App-download offer** on the public accept/reject delivery page (front-end only). **[S]**
- **(v) Merchant order IDs linkable** — indexed external ref + lookup/search + surfaced in DTOs;
  one design with (i). **[S–M]**
- **(xi) Fleet management software** — **exploratory**: survey off-the-shelf vs. building on the M13
  `VEHICLE` asset + routing `vanId` join. **[L]**

### AGNIVA — Merchant / Customer / Support / Platform
- **(vi) Per-user RBAC + budget** under a B2B account — richer member roles + per-member spend limit on
  top of the account credit check. **[M–L]**
- **(ix) Complete COD cash settlement** — close the downstream from hub-collection to **bank-confirmed**:
  verified (OTP) cash handoffs DA → hub → company bank, reconciliation against actual bank credit, and
  remittance/refund gated on funds landing. Sid consults on the DA cash-custody leg. **[L]**

### JOINT — Sid + Agniva
- **(vii) Auxiliary mode + (viii) Disposition states** — the DA-availability build, **design-doc first**;
  the open questions (threshold, reassign cutoff, return/restore policy) are decided together. **[L]**
- **(x) Global ETA** — the real ETA engine (Sid) + the proactive notification trigger (Agniva). **[M–L]**

### UNASSIGNED — business call
- **(ii) Pickup / cancellation charges** — business decides whether and how we charge; no engineering
  task until then.

**Rough balance.** Sid carries more solo items — including the two easy ones (iv) and (v), deliberately,
so Agniva isn't loaded with only trivia — plus the heavy ops greenfields (RTO mid-transit [L], Fleet
[L]). Agniva's two solo items are both substantive ((ix) complete COD settlement [L], (vi) per-user
RBAC+budget [M–L]). Both co-own all three joint builds. Same shape as Discussion 2: Sid = heavier ops
greenfields; Agniva = fewer, substantial platform builds on existing foundations.

---

## Dependencies & coordination
- **(i) Stub** and **(v) merchant order IDs** are both Sid's and share the merchant-reference concept —
  design the external-ref / stub-ref scheme once.
- **(x) Global ETA** needs Sid's routing/airline ETA engine before Agniva's notification trigger is
  meaningful — the engine lands first.
- **(iv) app-offer** sits on the public delivery-confirmation page, which is Sid's here — no hand-off.
- **(ix) COD settlement** — Agniva owns end-to-end; Sid consults on the DA cash-custody leg (mirrors the
  Discussion-2 DA COD-wallet #18 coordination).
- The **DA-availability pair** starts with a **joint design doc** — no code until the scenarios (break
  threshold, reassign cutoff, return/restore, auxiliary approval loop) are resolved together.

## Verification (per feature, end-to-end)
Each feature is "done" when it works across backend + the relevant console/app:
- **(i) Stub:** a B2B booking joins/creates a stub → the stub rolls up its orders' counts/price →
  visible in an admin stub view and linkable from its child orders.
- **(iii) RTO mid-transit:** a shipment sitting IN_FLIGHT / AT_DEST_HUB can be RTO'd → the return child
  is born at a re-entry point derived from its current location and carried back.
- **(iv) app-offer:** the receiver opens the no-login accept/reject link → sees the app-download CTA →
  the store link opens.
- **(v) merchant IDs:** a merchant books with their own order id → ops/merchant can search by it and
  reach the shipment.
- **(vi) per-user RBAC+budget:** a member with a spend limit is blocked past it while the account still
  has credit; role gates the actions they can take.
- **(ix) COD settlement:** collected cash moves DA → hub → bank as verified (OTP) handoffs → reconciled
  against a real bank credit → only then does remittance / refund fire.
- **(x) Global ETA:** a real ETA is computed system-wide → a live delay recomputes it → the existing
  `SHIPMENT_DELAYED` notification fires automatically (no manual revise).
- **(vii)+(viii) DA availability:** a DA marks a short break → territory held, not reassigned → returns
  and resumes; a longer break / auxiliary crosses the (jointly-decided) threshold → territory reassigns
  via the existing flood-fill, station manager sees the state.
- **(xi) fleet:** the exploratory review produces a build-vs-buy recommendation; if we build, a van is a
  registered vehicle joined to its routing `vanId` with document/maintenance tracking.

## Deliverable
This document (analysis + split) plus the Discussion-3 folder in Drive (`Project Discussion 3/`) —
Requirements + Split doc only this round.
