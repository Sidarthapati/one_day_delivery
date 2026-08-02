# Godspeed for Business (B2B portal) — status & context

Last updated: **2026-08-01**. Purpose: a single "where are we" snapshot of the B2B shipper portal —
what's built, what's left, how far we are from a Delhivery-class target, and the integrations needed
to run it for real. Companion docs: [`B2B-PORTAL-PLAN.md`](./B2B-PORTAL-PLAN.md) (the phase plan),
[`DELIVERY-PARITY-AND-PAYOUTS.md`](./DELIVERY-PARITY-AND-PAYOUTS.md) (gap table + payout mechanism),
[`COD-REMITTANCE-DESIGN.md`](./COD-REMITTANCE-DESIGN.md) (COD internals).

## 1. What this is

A separate web portal for **businesses that ship to their own customers** (`apps/business`, :3003),
alongside the consumer app. **We are the carrier** — own fleet + air across 5 cities — so, unlike
Shiprocket/Delhivery, there is **no multi-courier rate comparison**: the rate is our M2 B2B rate card
per account and serviceability is the M3 grid. An ADMIN operator console (`apps/admin`, :3004) backs
onboarding review and COD payouts.

Branches: `f-b2b-portal` in **both** repos (backend `one_day_delivery`, frontend `oneday-web`).
All work is on that branch; nothing deployed yet.

## 2. What's built (P0 → P3 + tail) ✅

**Onboarding & identity**
- Multi-step signup wizard → KYC verdicts → pending/active gate. Business onboarding request
  (`POST /auth/request-business-onboarding`), admin approve/reject, account provisioning on approve.
- **KYC** via swappable `KycPort` (`SandboxKycAdapter`, sandbox.co.in): GSTIN + PAN verified live
  (PAN derived from GSTIN). Account state machine UNVERIFIED→KYC_PENDING→ACTIVE|MANUAL_REVIEW→ACTIVE|REJECTED.
- **Auto-approval**: clean KYC + small merchant → instant activation; flagged type / large merchant → admin queue.
- **Volume band** (parcels/month `0-200 … 2000+`); **2000+ = big merchant → always admin review**.

**Booking**
- Single credit booking (`/ship`) → `POST /api/v1/b2b/shipments` (PO ref, parties, dims, declared value),
  with interactive Google-Maps address search + draggable pin → real lat/lon.
- **Bulk upload** (`/bulk`): one pickup, many destinations; client-side geocode-every-row via `@oneday/maps`,
  review/flag gate, then `POST /api/v1/cart/items/bulk` → credit checkout `POST /api/v1/cart/checkout`.
- Shipments list + detail (lane, parties, credit billing, linked invoice).

**Money**
- **Credit**: ship-now against `B2bAccount` credit limit / outstanding / payment terms.
- **COD remittance ledger** (the buyer→us→vendor flow): `cod_collection` per COD shipment
  (AWAITING_COLLECTION→COLLECTED→REMITTED / CANCELLED) + `cod_remittance` payout batches
  (gross−fee=net, `RMT/FY/NNNNNN`, PENDING→PAID). Delivery/cancel driven by a state-transition listener.
  Vendor ledger `/remittances`; admin payout console `/cod`. COD on single `/ship` **and** bulk.
- **Payout bank account** (this tail): capture + **penny-drop verification** + required-on-file before
  a payout. Port-based (`PayoutPort`): `ManualPayoutAdapter` (default, no provider) / `RazorpayXPayoutAdapter`
  (real, gated). Vendor `/bank` page.
- **GST invoices**: lazy-generated tax invoice (`GS/FY/NNNNNN`, SAC 996812), printable PDF per invoice
  (consumer + business), `EInvoicePort` (IRP seam, mock).

**Operator console (`apps/admin` :3004)**
- Onboarding & KYC review queue (approve/reject with reason, KYC verdicts, volume band, large-merchant badge).
- COD payout worklist → create remittance → mark paid (UTR) / provider payout.

**Consumer COD withdrawn** — B2C/C2C is prepaid-only (the old "pay cash at pickup" was removed).

## 3. Architecture at a glance

- **Backend** (`orders` module mostly): `B2bAccount`, cart API, `CodRemittanceService`, `BankAccountService`,
  ports `KycPort` / `PaymentPort` (Razorpay) / `PayoutPort` (RazorpayX) / `EInvoicePort` /
  `B2bProvisioningPort`. Migrations through `V4_27` + `V1_13`. Global Jackson snake_case; global
  Idempotency filter on `/api/v1` POSTs. Events over RabbitMQ (CloudAMQP).
- **Frontend** (Turborepo/pnpm, Next 16 + Mantine 9): `apps/business`, `apps/admin`; shared
  `@oneday/ui`, `@oneday/api` (snake_case typed client), `@oneday/maps`.

## 4. What's left

**P4 — 4 of 5 DONE & verified live (2026-08-02, branch `f-b2b-portal`):**
- ✅ **Wallet** — `wallet_balance_paise` + append-only `wallet_transaction` ledger (V4_28); recharge via
  `PaymentPort` (mock/Razorpay) → `POST /api/v1/wallet/recharge/order|confirm` (+ dev `mock/recharge`);
  booking funding source (`FundingSource` on `B2bBookingRequest`/`shipments`, default
  `creditLimit>0 ? CREDIT : WALLET`; WALLET debits with a 402 on shortfall); cancellation refunds the
  wallet. Portal **Wallet** page + funding selector on `/ship`.
- ✅ **Developer API keys + webhooks** — API keys **reuse M1's** `POST/GET/DELETE /auth/api-keys` +
  `X-Api-Key` machine-auth (already booked/tracked as the account). New webhook delivery:
  `webhook_delivery` (V4_29), `WebhookService`/`WebhookDispatcher` (`@TransactionalEventListener`
  AFTER_COMMIT → HMAC-SHA256 signed POST to `webhook_url`, async, best-effort), `DeveloperController`
  (`/api/v1/developer/webhook` get/put/test/deliveries). Portal **Developers** page.
- ✅ **Sales-lead capture** — `sales_lead` (V4_30); public `POST /api/sales/leads` (outside `/api/v1` so
  idempotency-exempt; permitted in `SecurityConfig`); admin `GET/PATCH /api/v1/admin/sales/leads`.
  Public business `/contact-sales` form + admin **Sales leads** queue.
- ✅ **White-label tracking** — branding cols + per-shipment `track_token` (V4_31, back-filled for
  existing B2B); public `GET /api/v1/track/{token}` (no auth) → tracking + branding;
  `/api/v1/branding` get/put. Public branded page `apps/business/app/t/[token]`, **Settings** branding
  form, "Copy tracking link" on shipment detail.
- ⏸️ **Team members / roles (ON HOLD — deferred by user 2026-08-02)** — the one P4 item not built. It's
  a *scale* feature (multiple logins per business, OWNER/STAFF), which Shiprocket/Delhivery One do have,
  but it is **not a pilot blocker** (one login per business works day one) and it is the single most
  invasive change in P4 — membership-aware account resolution would replace the single-owner
  `findByOwnerUserId` across **every** B2B endpoint. Design still in the approved plan (`b2b_account_member`,
  invite via M1 `UserService.register`/`changeRole`/`getUserByEmail`) for when it's worth the refactor.

**Track B — ALL DONE & verified live (2026-08-02, branch `f-b2b-portal`):**
- ✅ **Shipping-label PDF (Model A) + seller block** — `GET /api/v1/shipments/mine/{ref}/label`
  (`ShipmentLabelResponse`; AWB/barcode = shipment ref; seller name+GSTIN from the B2B account).
  Portal print page `apps/business/app/(app)/shipments/[ref]/label` renders a **client-side Code128**
  barcode (self-contained `lib/barcode.ts`, no external host) + `window.print()` A6 label; "Print label"
  on shipment detail. (Courier tax invoice already carries the merchant's name+GSTIN as the buyer, so no
  invoice change was needed — the seller-of-goods block belongs on the label.)
- ✅ **e-Way bill capture + saved pickup warehouses** — `shipments.eway_bill_number` (V4_32), captured
  on `/ship` (advisory; NIC API deferred to Track A) and shown on the label. Warehouses reuse the address
  book via a new `WAREHOUSE` `AddressLabel` — `/ship` offers "Pickup from a saved warehouse" + "Save this
  pickup as a warehouse". (Bulk-upload e-way column skipped: the bulk template uses strict exact-header
  matching, so a new column would break existing merchant sheets — a conscious follow-up.)
- ✅ **Notification framework (log-sink default).** Orders-side `SmsSender`/`EmailSender` seams with
  `LoggingSmsSender`/`LoggingEmailSender` defaults (`@ConditionalOnProperty notify.{sms,email}.provider=log`,
  matchIfMissing) + gated real adapters `Msg91SmsSender`/`SendGridEmailSender` (off by default, env-only
  creds, best-effort HTTP — untested seam, like `RazorpayXPayoutAdapter`). `Notifier` composes them
  (async, best-effort) with a milestone allow-list; `NotificationDispatcher` fires on `ShipmentTransitioned`
  AFTER_COMMIT (PICKED_UP/out-for-delivery/DELIVERED/failed/RTO/CANCELLED → sender, +receiver for delivery
  milestones); COD remittance `markPaid`/`payout` → merchant confirmation. **OTP stays on the existing auth
  `OtpSender` seam; onboarding-decision email is a small auth-side follow-up (same pattern).** VERIFIED:
  cancelling a shipment logged `[notify:sms] → +91… has been cancelled` on the async dispatch thread.
- ✅ **DA COD cash reconciliation (backend + admin).** `cod_collection.collected_by_da_id` (V4_33, set from
  the transition actor on COLLECTED) + `cod_cash_deposit` ledger; `CodCashService` (DA `recordDeposit`/`daSummary`,
  admin `reconciliation`/`allDeposits`/`reconcile`→RECONCILED|DISCREPANCY). Endpoints: DA
  `POST /api/v1/cod/da/deposits` + `GET /api/v1/cod/da/summary` (`DaCodController`, DELIVERY_ASSOCIATE);
  admin `GET /api/v1/admin/cod/cash/reconciliation`, `GET .../deposits`, `PATCH .../deposits/{id}`.
  Admin **COD cash recon** page (per-DA collected/deposited/outstanding + reconcile/flag). **Driver-app
  "record cash" screen = separate repo, scheduled.** VERIFIED: deposit → DA summary → admin recon row →
  PATCH reconcile → RECONCILED.

**Deferred (conscious):**
- **DA per-rider COD cash-collect & deposit reconciliation** — how a rider records what they collected
  and deposits it (ops layer). Documented in COD design §8; not built.
- **GSTIN-less onboarding** via Aadhaar + PAN / DigiLocker (Meri Pehchaan) — for merchants without a GSTIN.
- **Order/product (SKU) layer**, sales channels (Shopify/Amazon), e-way bill, seller-details-on-label,
  saved pickup warehouses — see the parity table.

## 5. Distance from a Delhivery-class target

**Verdict: pilot-ready; not yet a self-serve OMS.** We match Delhivery on the *carrier* essentials a B2B
shipper needs — onboarding/KYC, credit, single + bulk booking, COD collection **and** remittance with a
verified payout account, GST invoices, an ops console. We are **not** an order-management platform:
no product/SKU catalog, no e-commerce channel sync, no seller-details/e-way-bill/warehouse model. Those
are a separate epic and a positioning choice (carrier vs OMS), **not** blockers for the Sept-1 pilot.
Full gap table with build-now/defer calls: [`DELIVERY-PARITY-AND-PAYOUTS.md`](./DELIVERY-PARITY-AND-PAYOUTS.md) §2.

Maturity by area: onboarding **90%** · booking **85%** · bulk **80%** · COD/remittance **85%
(rider-collect reconciliation is the hole)** · invoicing **80% (e-invoice/IRP is a mock)** · payouts **70%
(manual works; RazorpayX auto needs go-live)** · order/SKU management **0% (deliberate)**.

## 6. Integrations needed to run this properly

| Integration | Purpose | Status | To go live |
|---|---|---|---|
| **KYC** — sandbox.co.in | GSTIN/PAN (later Aadhaar) verification | Live trial key, GSTIN+PAN working | Paid subscription; add Aadhaar/DigiLocker for GSTIN-less |
| **Payments** — Razorpay | Prepaid cart checkout + refunds | Adapter done; **mock default** | Live keys (`RAZORPAY_LIVE=true` + key id/secret env) |
| **Payouts** — RazorpayX | COD remittance to merchant bank (penny-drop + payout) | Adapter done; **manual default** | RazorpayX account + balance; `payout.provider=razorpayx` + keys; **2 webhook handlers** (`fund_account.validation.completed`, `payout.processed`) |
| **Maps** — Google Maps Platform | Address search, geocode, pin | Key present; **referrer-blocked locally** | Allow referrers (`localhost:3000/3003/3004/*` + prod domains); enable Maps JS + Places + Geocoding APIs; **enable billing** |
| **SMS / OTP** | Phone OTP + shipment/payout SMS | `LoggingOtpSender` stub (logs code) | Real provider (MSG91 / Twilio) behind `OtpSender` |
| **Email** | Onboarding, payout, invoice notifications | Not wired | Provider (SES / SendGrid) + templates |
| **e-Invoice / IRP** — GSP | GST e-invoice IRN/QR | `EInvoicePort` mock | GSP/IRP integration when turnover threshold applies |
| **e-Way bill** — NIC API | Goods > ₹50k interstate | Not built | NIC e-way bill API + capture field |
| **Event bus** — CloudAMQP (RabbitMQ) | Internal events | Done | (infra: prod broker + queues) |
| **Database** — Render Postgres | Persistence | Done (shared dev DB) | Prod DB + Flyway on deploy |

**Secrets rule:** KYC / Razorpay / RazorpayX / Maps keys, DB creds, broker URLs are **env-only, never
committed** (`.env` is gitignored).

## 8. Finish line — everything B2B by Monday (Aug 3, 2026)

**Goal: all remaining B2B work — P4 + every launch item below — done and deployed by Monday Aug 3**
(this doc updated Sat Aug 1; the window is the Sat–Sun–Mon weekend). **No trimming.** The only item
that stays out is the OMS/SKU-catalog + Shopify/Amazon channel epic — that was mutually agreed as a
separate post-launch product direction (carrier vs OMS), never part of launch scope, so it is *not*
a trim. Everything else ships.

**How this is doable in a weekend:** the hard architecture already exists (ports for KYC, payments,
payouts, e-invoice; the cart/COD/booking spine; a proven customer `/track`). The remaining work is
mostly **filling adapters + a few screens**, which is fast. The real constraint is **not our code —
it's third-party go-lives with external approval lead times**. Those must be kicked off **now, in
parallel**, and each has a mock/fallback so nothing blocks the Monday code-complete.

### 8.1 Two tracks, run in parallel

**Track A — external go-lives (owner: you, start immediately; some approvals are outside our control):**

| Integration | Start now because… | Fallback if not cleared by Mon |
|---|---|---|
| **Razorpay LIVE** (collection/wallet) | Razorpay activation/KYC can take a day | Wallet runs on the existing **mock gateway**; flip `razorpay.live=true` when keys land |
| **RazorpayX** (payouts) + fund balance | Account + balance funding takes time | **Manual payout console** already works (finance NEFT + UTR) |
| **SMS provider** (MSG91/Twilio) + **DLT template registration** | ⚠️ **Indian DLT template approval is the slowest — can take days** | `LoggingOtpSender` + queued sends; swap adapter when DLT clears |
| **Email** (SES/SendGrid) | SES prod-access request can take ~a day | Console/log sink; swap when approved |
| **Google Maps** (billing + referrers + APIs) | Near-instant if a billing account exists | — (do this first, it's config) |
| **KYC paid plan** (sandbox.co.in) | Move off the trial key | Trial key keeps working short-term |
| **Prod hosting** (Render services for backend + business + admin) | Provisioning + DNS | Staging URLs for launch, prod DNS to follow |

**Track B — code/build (owner: me, all landable this weekend, behind the existing ports):**
wallet · shipping-label PDF · seller details on label/invoice · DA COD cash reconciliation · SMS +
email adapters + notifications · white-label tracking · developer API keys + webhooks · team/roles ·
e-way bill capture · saved pickup warehouses.

### 8.2 Feature build list (all in scope by Monday)

| Feature | Effort | Note |
|---|---|---|
| **Wallet-first** (ledger + recharge + balance-gates-orders; credit stays for approved accounts) | M | Credit machinery exists; add wallet + flip default |
| **Printable shipping-label PDF** (address block + barcode/AWB) | S | M8 already mints the barcode + `LABEL_GENERATED`; render a label |
| **Seller details on label/invoice** (name, GSTIN, address) | S | Data on `B2bAccount`; surface on label + invoice |
| **DA per-rider COD cash reconciliation** (rider records collected + deposit) | M | Closes the "COLLECTED is inferred" hole; needed for COD day 1 |
| **SMS + Email notifications** (OTP, tracking, payout, onboarding) | M | Adapters behind `OtpSender` + a new `Notifier` port |
| **White-label tracking** for B2B recipients | S | Reuse customer `/track`; brand per account |
| **Developer API keys + webhooks** (P4) | M | Key issuance + signed webhooks on shipment events |
| **Team members / roles** (P4) | S | Multiple users per `B2bAccount` |
| **Sales-lead capture form** (P4) | S | Public form → admin queue |
| **e-Way bill capture** (>₹50k) | S | Field + validation now; NIC API call when live |
| **Saved pickup locations / warehouses** | S | Promote the address book to named pickups |

*(Post-launch, unchanged: order/SKU + channel sync, NDR/returns portal, reports/analytics,
e-invoice/IRP, weight-dispute.)*

### 8.3 Weekend sprint (Sat Aug 1 → Mon Aug 3)

**Saturday — kick off Track A + build the money spine**
1. **You:** start every Track-A signup now (Razorpay, RazorpayX, SMS+DLT, email, Maps, KYC, hosting).
2. **Me:** **wallet** (ledger + recharge + balance gate, on mock gateway) · **shipping-label PDF** ·
   **seller details** on label/invoice.

**Sunday — COD, notifications, tracking, P4 remainder**
3. **DA COD cash reconciliation** (rider collect + deposit) · **SMS + Email** adapters wired to OTP /
   tracking / payout / onboarding.
4. **White-label tracking** · **developer API keys + webhooks** · **team/roles** · **sales-lead form** ·
   **e-way bill capture** · **saved warehouses**.

**Monday — integrate live, deploy, launch**
5. Swap in whichever Track-A keys have cleared (Razorpay/RazorpayX/SMS/email); anything not cleared
   runs on its fallback.
6. **Prod deploy** (backend + business + admin) · **real-parcel E2E dry run** · **launch**.

**Risks to manage (not trims):** (a) DLT SMS-template + Razorpay-live approvals are the only things
we can't force — mitigated by mock/fallback adapters, live-swappable without a redeploy of logic;
(b) prod deploy is real work — it runs Monday morning, not as an afterthought; (c) COD day-1 needs the
rider-reconciliation piece — it's on Sunday, ahead of Monday's dry run.

### 8.4 Progress — session 2026-08-02

**Landed & verified live** (booted vs the shared Singapore dev DB; migrations V4_28–V4_31 applied clean):
- **Wallet** — mock recharge credited the balance + wrote a ledger row; `X-Api-Key` machine-auth, revoke,
  and revoked-key-401 all confirmed.
- **Developer webhooks** — a **test webhook was actually delivered to httpbin (HTTP 200)** with the
  `X-Godspeed-Signature` HMAC header; API-key issue/reveal/revoke confirmed.
- **Sales leads** — public unauthenticated `POST /api/sales/leads` created a lead; admin list + PATCH→CONTACTED confirmed.
- **White-label tracking** — the migration back-filled a `track_token` on an existing B2B shipment; the
  public `GET /api/v1/track/{token}` (no auth) returned the tracking view + the merchant's branding.
- Frontend: full-workspace `pnpm -w typecheck` green (8/8); business/admin pages added (Wallet,
  Developers, Settings, public `/t/[token]`, public `/contact-sales`, admin Sales leads).

**Second batch — verified live** (migration V4_32 applied clean on boot):
- **Shipping label (Model A)** — booked a B2B Delhi→Mumbai shipment with an e-way bill;
  `GET /api/v1/shipments/mine/{ref}/label` returned the full label (AWB=ref, from/to blocks, seller
  name from the account, `eway_bill_number` echoed back).
- **e-Way bill** — `881122334455` persisted at booking and surfaced on the label.
- **Saved warehouses** — `POST /api/v1/addresses` accepted `label=WAREHOUSE`, the list filtered it,
  and delete returned 204 (smoke row cleaned up).
- Frontend: `pnpm -w typecheck` green (8/8) with the label print page, Code128 helper, ship-page e-way
  field + warehouse select/save, and the customer `AddressLabel` WORK→OFFICE fix.

**Third batch — verified live** (migration V4_33 applied clean on boot):
- **Notifications** — cancelling a shipment fired the async log-sink: `[notify:sms] → +91… : Godspeed:
  your shipment … has been cancelled.` (email skipped — no sender email). Both `LoggingSmsSender`/
  `LoggingEmailSender` logged their `provider=log` banner at startup.
- **DA COD cash recon** — recorded a deposit (₹2,500) → DA summary showed deposited/outstanding → admin
  reconciliation listed the DA row → `PATCH` reconcile → RECONCILED. (Smoke deposit deleted afterwards.)
- Frontend: `pnpm -w typecheck` green (8/8); admin **COD cash recon** page builds (`/cod-cash` route).

**Team members/roles — ON HOLD** (user call, 2026-08-02): scale feature, not a pilot blocker, invasive
single-owner refactor — see §4.

**Status: P4 = 4/5 (Team on hold) + Track B = fully done.** The whole B2B portal scope is complete
except the deliberately-deferred Team feature. Nothing pushed — all commits stay local on `f-b2b-portal`
(both repos) pending your go-ahead. Demo-account test artifacts reset (smoke warehouse + smoke deposit
deleted; demo shipment `1DD-DEL-20260802-00001` now CANCELLED from the notification test).

### 8.5 Full E2E feature-test — session 2026-08-02 (Playwright MCP + API sweep)

Drove the real UIs (business :3003, admin :3004) end-to-end and ran a 26-call backend API sweep.
**Result: everything works.** Verified: wallet (recharge order→mock-pay→confirm→ledger; wallet-funded
booking debit −₹581.74; cancel refund +₹581.74; UI recharge modal + ledger table); developer keys
(create/reveal-once/list/revoke UI; `X-Api-Key` auth → 401 after revoke; webhook config + test +
deliveries log — a real `shipment.state_changed` webhook fired on cancel); sales leads (public POST
201, admin list + status-change menu PATCH → WON); white-label (branding get/set; public `/t/{token}`
branded, unauthenticated); shipping label (A6 print page renders a real Code128 barcode + seller block
+ e-way); saved warehouses (create/list/delete + `/ship` selector appears); notifications (cancel →
async SMS+email log-sink); DA COD recon (deposit → admin reconciliation table → Reconcile → RECONCILED);
credit-path regression (default `funding_source`→CREDIT, outstanding +/− on book/cancel). Authz/error
cases pass (B2B→admin 403, bad track token 404, unauth 401, revoked key 401).

**Two bugs found & fixed** (uncommitted, on `f-b2b-portal`):
1. **Wallet recharge broke against real Razorpay** — receipt `"wallet-recharge-"+accountId` was 52 chars
   (Razorpay caps at 40) → every recharge 402'd when `RAZORPAY_LIVE=true`. Shortened to `"wr-"+32-hex`
   (35 chars) in `WalletServiceImpl`, plus a defensive 40-char truncation in `RazorpayPaymentAdapter`.
   Re-verified: order→pay→confirm credits the wallet.
2. **Shipment detail always showed "credit"** — the detail view hard-coded the billing badge, so
   wallet-funded B2B shipments wrongly read "Billed to account · credit". Added `funding_source` to
   `MyShipmentDetailResponse` (+ `@oneday/api` type) and the page now renders "Paid from wallet" vs
   "Billed to account · credit". Verified the badge flips correctly.

Backend rebuilt + restarted clean; frontend `pnpm -w typecheck` green (8/8). Test artifacts cleaned
(deposit + sales lead deleted, demo-account branding reset). Local map on `/ship` shows a Google Maps
referrer error — that's the API key's referrer restriction in local dev, not a code defect.

## 9. Local run

Backend: `mvn spring-boot:run -pl app` (JDK 21) on :8080 vs shared dev DB (`source .env`).
Frontend: `pnpm --filter @oneday/business dev` (:3003), `--filter @oneday/admin dev` (:3004).
Logins: admin `admin@oneday.in` / `godspeed2026`; business `b2b.demo@oneday.test` / `godspeed2026`.
