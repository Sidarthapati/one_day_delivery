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

**P4 (next):** wallet ledger + recharge (+ auto-top-up from remittances), developer/API keys + webhooks,
team members / roles, sales-lead capture form, white-label tracking page.

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

## 7. Local run

Backend: `mvn spring-boot:run -pl app` (JDK 21) on :8080 vs shared dev DB (`source .env`).
Frontend: `pnpm --filter @oneday/business dev` (:3003), `--filter @oneday/admin dev` (:3004).
Logins: admin `admin@oneday.in` / `godspeed2026`; business `b2b.demo@oneday.test` / `godspeed2026`.
