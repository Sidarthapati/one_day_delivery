# Godspeed for Business — B2B Shipper Portal: Plan

**Status:** v0.3 · 2026-07-27 — P1 + P2 core portal + universal invoicing BUILT (see Build status)
**Branches:** backend `f-b2b-portal` (one_day_delivery) · frontend `f-b2b-portal` (oneday-web)
**Owner:** Sid

## Build status (2026-07-26)

**Locked decisions:** wallet-vs-credit → credit-first + prepaid-per-batch (wallet → P4); KYC → Sandbox.co.in
behind `KycPort` (live keys in gitignored `.env`, `KYC_LIVE=false` default → mock); Aadhaar → deferred
(GSTIN+PAN+bank only); invoices/remittance → in `orders`, no new module.

**Invoicing is UNIVERSAL, not B2B-only.** Every order (C2C/B2C/B2B) gets a GST tax invoice for Godspeed's
service (SAC 996812). This corrects the original plan, which scoped invoices under B2B billing.

**Done (compiles / typechecks green, committed on the two `f-b2b-portal` branches):**
- Backend P1 backbone: `KycPort` + GSTIN/PAN/bank DTOs + `SandboxKycAdapter` (mock default); business
  onboarding (`POST /auth/request-business-onboarding` runs KYC, records PENDING; `V1_12`); approve →
  create user + provision `B2bAccount` via `B2bProvisioningPort`; account KYC state machine (`V4_23`);
  `GET /api/v1/b2b/accounts/mine`.
- Universal invoicing: `Invoice` + `invoices` table & serial sequence (`V4_24`); lazy generation from
  stored pricing (`GS/{FY}/NNNNNN`, CGST/SGST split w/ TODO place-of-supply); `EInvoicePort` (IRP seam) +
  mock; `GET /api/v1/invoices/mine` + `/{shipmentRef}`.
- Frontend P1: `@oneday/api` extended (business onboarding, accounts/mine, invoices); `apps/business`
  multi-step **signup wizard** → KYC verdicts + pending-approval screen; landing routes to it.
- **Frontend P2 (oneday-web commit `8643474`):** business **session** (`od_business_session` /
  `od_business_token`) + guarded `(app)` shell + **account gate** (fetches `accounts/mine`, gates the
  whole portal behind activation — pending / rejected / active). `/login` (email+password → `/dashboard`);
  `/dashboard` (credit summary + verification badges + recent B2B shipments); `/ship` (single credit
  booking → `POST /api/v1/b2b/shipments`, PO ref, parties, dims, declared value); `/shipments` + `/[ref]`
  (filter/search table + full detail with linked invoice); `/invoices` (GST invoices, SAC 996812).
  Shared `@oneday/api` gained `b2b.book`/`b2b.cancel` + `B2bBookingRequest`.
- **Universal invoice download (consumer):** customer `/orders/[ref]/invoice` — a print-ready tax-invoice
  document (seller/buyer, SAC line item, CGST/SGST-or-IGST split, browser print-to-PDF), linked from the
  order-details payment card. Also merged the `f-order-details` branch (customer order-details page) into
  `f-b2b-portal` so both live together.

**Next:** admin KYC review queue UI (**needs a surface decision** — no admin app exists yet; approve/reject
M1 endpoints already work); then P3 (bulk cart checkout + CSV import, COD remittance ledger, invoice
PDF/statements), P4 (wallet, developers/webhooks, team, sales lead, white-label tracking). Consignee/pickup
address book (P2 tail) still open.

### Address resolution (done for single orders; primitive ready for bulk)

Every order needs **coordinates** — M3 resolves the serviceable hex from lat/lon; text + pincode alone is
only a coarse pincode-prefix fallback. Approach:

- **Shared `@oneday/maps` package** (oneday-web commit `75dd77e`): the consumer's vendor-neutral
  `MapsProvider` (Google autocomplete + pin-drag reverse-geocode) promoted out of `apps/customer` so all
  apps + the bulk flow share one implementation. Added **`geocodeText(query, {pincode})`** — forward-geocode
  a raw text address → best match + **confidence** (ROOFTOP/RANGE→high, GEOMETRIC→medium, else low) +
  partial-match flag + pincode-agreement check.
- **Single `/ship` (done):** interactive address search + a draggable map pin per party, each run through
  `/serviceable-at`; booking carries real origin/dest lat/lon (+ derived IATA city, editable pincode).
- **Bulk Excel (P3) — geocode-*before*-place, review gate:** parse the sheet → `geocodeText` **every** row
  automatically (no manual picking — Google's best match is taken) → **validation table**: high-confidence
  rows auto-accept; low-confidence / pincode-mismatch / partial-match rows are flagged for a quick inline
  fix (edit text + re-geocode, or pin) or the shipper re-uploads a corrected sheet. Only after review are
  orders placed. Chosen over "place-then-convert" because serviceability + delivery success both hinge on
  the coordinate being right. Client-side geocoding for now (consistent, no new secret); add a server-side
  cache/`GeocodePort` if volume needs it.

**Remaining P2 caveats:** Migrations (`V1_12`/`V4_23`/`V4_24`) still unverified against a live DB. Seller
GSTIN/registered address on the consumer invoice render as placeholders until the entity is GST-registered
(`NEXT_PUBLIC_SELLER_GSTIN`). `geocodeText` uses `componentRestrictions.postalCode` as a *bias* — Google may
relax it, so the pincode-agreement check (not the restriction) is what actually flags mismatches.

**Not yet done / caveats:** `SandboxKycAdapter` live HTTP endpoints must be verified against Sandbox's API
docs before `KYC_LIVE=true`; invoice CGST/SGST/IGST split uses an intra-state assumption pending real
place-of-supply logic (CA review); invoice generation is lazy (on first fetch) — no back-fill job yet.

A separate, operator-grade web portal ("Godspeed for Business") for **business shippers** —
companies that send parcels to *their* customers. It sits alongside the consumer Customer Web
(C2C/B2C-retail) and reuses the shared design system + API client, but has its own IA, its own
onboarding (KYC/KYB), and its own money model. Reached from a **"Are you a business? →"** entry on
the consumer login.

This plan reconciles the **industry-standard flow** (Shiprocket / Delhivery style, per the product
brief) with **what our platform already has**. The generic guide assumes a multi-courier aggregator;
**we are the carrier** (own fleet + air, 5 pilot cities), so several generic pieces don't apply and
several already exist.

---

## 0. What we already have (do NOT rebuild)

| Capability | Where | Notes |
|---|---|---|
| **B2B account model** | `orders` · `B2bAccount` | `accountName`, `gstin`, `billingEmail`, `creditLimitPaise`, `outstandingBalancePaise`, `paymentTermsDays`, `rateCardId`, `webhookUrl/Secret`, `cityId`, `isActive`, `ownerUserId` |
| **B2B single booking** | `orders` · `POST /api/v1/b2b/shipments` | Requires `b2bAccountId`, **`purchaseOrderRef`**, mandatory `declaredValuePaise`; `SELECT FOR UPDATE` credit check → increments `outstandingBalancePaise` |
| **Bulk / cart booking** | `orders` · `CartController` `/api/v1/cart` | `GET` cart, `POST /items`, `PUT/DELETE /items/{id}`, `POST /payment-order`, `POST /checkout` (`CartCheckoutRequest{ b2bAccountId, razorpay… }`) — **bulk backbone already wired** |
| **B2B pricing** | `pricing` (M2) | Versioned B2B rate cards; account carries its own `rateCardId` (demo card @15% off) |
| **Onboarding request → approve** | `auth` (M1) | `POST /auth/request-onboarding` (`requestedRole: B2B_USER\|B2C_CUSTOMER`) → ADMIN `POST /onboarding-requests/{id}/approve\|reject` |
| **Roles + JWT + ownership** | `auth` (M1) | `B2B_USER` role; B2B endpoints already gate on role **and** `B2bAccount.ownerUserId` ownership |
| **Serviceability** | `grid` (M3) | `GET /api/grid/serviceable-at` (pincode/hex) — our "pincode serviceability checker" |
| **Payments + refunds** | `orders` · `PaymentPort` (Razorpay) | Prepaid capture + refund; mock in non-prod |
| **Shipment history** | `orders` · `GET /api/v1/shipments/mine` | Already returns B2B shipments for the owner |

## 0b. What the generic guide assumes that we DON'T need

- **Multi-courier rate comparison / courier selection** — N/A. We are the sole carrier; the rate is our
  account rate card. No "compare couriers" screen.
- **Pincode courier-coverage matrix** — replaced by M3 grid serviceability (5 cities).
- **Storefront plugins (Shopify/WooCommerce)** — out of pilot scope; API + webhooks (fields already on the
  account) cover integration later.

---

## 1. Scope decisions (our reality)

1. **Business model:** B2C-shipper. The business ships to *its* end customer. COD is collected from the
   **end customer** and must be **remitted to the business** → we need a **COD remittance ledger** (new).
   (Today's consumer COD is customer-paid, no remittance leg.)
2. **Money model — reconcile wallet vs credit.** The generic flow leads with a **prepaid wallet**
   (mandatory min recharge, auto-debit per shipment) and treats **credit/postpaid** as a human-reviewed
   upgrade. We already built the **postpaid credit** side (`creditLimit`/`outstanding`/`paymentTerms`).
   **Proposal for the pilot:**
   - **Ship credit-first** (exists): admin sets a credit limit at approval; bookings draw down
     `outstandingBalancePaise`; monthly settlement per `paymentTermsDays`.
   - **Add a light prepaid path** using the existing Razorpay `payment-order`/`checkout` per cart, so a
     business with **no credit line** can still pay-per-batch.
   - **Full wallet ledger** (stored balance, recharge, auto-debit, low-balance alerts) = **Phase 4**, not
     pilot-critical. *(Decision to confirm — see §8.)*
3. **KYC/KYB via a provider behind a port.** Introduce a `KycPort` (mirrors `PaymentPort`/`GhaPort`): a
   swappable interface with a **sandbox/test adapter now** and a real provider later. Recommended provider
   **Sandbox (sandbox.co.in)** — one surface for GSTIN, PAN, Aadhaar OTP, bank penny-drop; self-serve test
   creds, no sales call to integrate. Verifications: **GSTIN (KYB)**, **PAN**, **Aadhaar OTP** (signatory),
   **bank penny-drop** (for COD remittance). Automated pass → active; any fail → **manual review queue**
   (user can browse the dashboard but not ship/enable COD until cleared).
4. **Credit line = human-reviewed.** Self-serve *request* → internal approval queue (reuses the M1
   approve/reject pattern), gated on shipment history/volume. Never auto-approved.
5. **Separate app, shared system.** New Next app `apps/business` (`@oneday/business`, port 3003) in the
   oneday-web turborepo; shares `@oneday/ui` + `@oneday/api`; distinct IA. (Mirrors `hub`/`station`.)

---

## 2. Target onboarding flow (adapted to our stack)

| # | Step | Our implementation |
|---|---|---|
| 1 | **Sign up** — name, business email, phone, password; phone/email OTP verify | Extend M1: business sign-up creates the user (`B2B_USER` requested) in an **unverified** account state; reuse existing phone-OTP (`/auth/otp/*`). Can browse, cannot ship. |
| 2 | **Activate business** — choose entity type (Proprietorship / Partnership / Pvt Ltd / LLP) | New `business_type` on the onboarding/account record. |
| 3 | **KYC/KYB** — GSTIN → auto-verify; PAN → auto-verify; Aadhaar OTP (signatory) | `KycPort` calls; store verification results + status per check. Fail → **manual review queue**. |
| 4 | **Bank verification** (COD remittance) — account no + IFSC, penny-drop | `KycPort.verifyBankAccount`; store masked bank details; 3 attempts → manual fallback (upload cheque). |
| 5 | **Pickup location(s)** — one+ warehouse addresses, each serviceability-checked | New `b2b_pickup_location` table; each checked via M3 `serviceable-at`. |
| 6 | **Funding** — credit line (admin-set) *or* prepaid per-batch | Credit: exists. Prepaid: existing Razorpay cart checkout. (Wallet recharge = Phase 4.) |
| 7 | **Go live** — account `ACTIVE`; can book (single + bulk), see billing | `B2bAccount.isActive = true` + verification state `ACTIVE`; provisioned at approval. |

**Where a human enters:** manual KYC fallback, manual bank fallback, **credit-line approval** (always),
risk review for high COD/RTO. Large prospects can be routed to sales before self-serve (inbound "request a
demo") — a simple lead form, Phase 4.

---

## 3. Account lifecycle (state machine)

```
UNVERIFIED ──(submit KYC)──► KYC_PENDING ──(all auto-pass)──► ACTIVE
                                  │
                                  ├─(any auto-fail)──► MANUAL_REVIEW ──(admin approve)──► ACTIVE
                                  │                                   └─(admin reject)──► REJECTED
                                  └─(bank fail)──────► MANUAL_REVIEW
```

New/changed columns on `B2bAccount` (Flyway `orders` migration):
`verification_status` (enum), `business_type`, `pan`, `pan_verified`, `gstin_verified`,
`signatory_verified`, `bank_account_masked`, `bank_ifsc`, `bank_verified`, `kyc_submitted_at`,
`activated_at`, `rejection_reason`. Keep `isActive` as the hard ship/no-ship gate (derived from
`verification_status = ACTIVE`). Append-only KYC audit rows (a `b2b_kyc_check` table) — consistent with the
platform's append-only audit invariant.

---

## 4. KYC integration — `KycPort`

```
interface KycPort {
  GstinResult   verifyGstin(String gstin);              // → legalName, status, address
  PanResult     verifyPan(String pan, String name);
  AadhaarOtpRef requestAadhaarOtp(String aadhaar);      // OTP to Aadhaar-linked mobile
  AadhaarResult verifyAadhaarOtp(AadhaarOtpRef, String otp);
  BankResult    verifyBankAccount(String accountNo, String ifsc, String beneficiaryName); // penny-drop
}
```

- `SandboxKycAdapter` (real, `kyc.live=true`) + `MockKycAdapter` (deterministic pass/fail for `!prod`,
  default). Creds via env only — **never commit KYC keys** (`KYC_LIVE`, `KYC_API_KEY`, `KYC_API_SECRET`).
- Cost is immaterial at pilot scale (~₹1–5/check). Test/sandbox creds are self-serve.
- Every call writes an append-only `b2b_kyc_check` row (provider, type, request ref, verdict, at).

---

## 5. Backend work (by module)

**M1 `auth`**
- Extend business onboarding to capture business fields (or a dedicated `POST /auth/request-business`):
  companyName, businessType, gstin, billingEmail, expected monthly volume.
- On **approve**, in addition to granting `B2B_USER`, **provision a `B2bAccount`** (link `ownerUserId`,
  set gstin/billing, default `rateCardId`, initial credit limit=0 unless credit approved, `paymentTermsDays`).

**M4 `orders`**
- `B2bAccount` migration (§3 columns) + `b2b_kyc_check` + `b2b_pickup_location` tables.
- `GET /api/v1/b2b/accounts/mine` — account status, verification state, credit limit + outstanding,
  payment terms, rate-card summary, pickup locations.
- KYC submit/verify endpoints (`POST /api/v1/b2b/kyc/*`) delegating to `KycPort`; drive the state machine.
- **COD remittance ledger** (new): capture COD collected per B2B shipment → owed-to-business balance;
  `GET /api/v1/b2b/remittances`; settlement records. *(Phase 3.)*
- **Invoices** (new): GST-compliant per-shipment + monthly consolidated; `GET /api/v1/b2b/invoices`.
  *(Phase 3.)*

**New `KycPort`** (in `common` ports + adapter in `orders` or a small `kyc` seam).

---

## 6. Frontend — `apps/business` (`@oneday/business`, :3003)

Distinct, dashboard-first, table-heavy IA (deliberately **not** the consumer's single-parcel, map-first
flow). Shares `@oneday/ui` theme + `@oneday/api`.

| Screen | Purpose | Phase |
|---|---|---|
| **Sign in / Business sign-up** | Email+password sign-in; sign-up = KYC onboarding wizard → "pending approval" | P1 |
| **Onboarding wizard** | Multi-step: business type → GSTIN → PAN → Aadhaar OTP → bank → pickup → funding → live; per-step status (verified/pending/failed/review) | P1 |
| **Dashboard** | Credit limit vs outstanding, month's shipments + spend, recent shipments, quick actions | P2 |
| **Book (single)** | One consignment: PO ref + declared value (B2B fields), consignee, pickup location | P2 |
| **Shipments** | Filterable/exportable table (PO, status, lane, date) | P2 |
| **Consignees / pickups** | Saved receivers + pickup warehouse locations | P2 |
| **Bulk / Cart** | Add many consignments → one checkout (wires `/api/v1/cart`); CSV import | P3 |
| **Billing** | Outstanding, statement, COD remittance dashboard, invoices, POs | P3 |
| **Developers** | API key + webhook URL/secret (fields exist on the account) | P4 |
| **Account & KYC** | Company details, GSTIN, verification status, team members | P4 |

**Entry point (customer → business):** on the consumer login, a subtle full-width
**"Shipping for a business? → Godspeed for Business"** action → `NEXT_PUBLIC_BUSINESS_URL`
(env; default `http://localhost:3003` in dev). Business app links back to consumer for personal senders.

---

## 7. Phased delivery

- **P0 — Entry + scaffold** *(this pass)*: `apps/business` scaffold; consumer-login entry button; env
  wiring; api-client stubs for b2b/kyc/account.
- **P1 — Onboarding / KYC**: `KycPort` + sandbox/mock adapter; M1 business onboarding + account
  provisioning on approve; account state machine + migrations; business sign-in + KYC wizard +
  pending-approval screen; ADMIN review queue.
- **P2 — Core ops**: dashboard, single B2B booking (PO ref), shipments table, consignee/pickup book,
  `GET accounts/mine`.
- **P3 — Bulk + money**: cart bulk checkout + CSV import; COD remittance ledger; GST invoices; billing UI.
- **P4 — Growth**: prepaid wallet ledger; developers (API keys/webhooks); team members; sales lead form;
  white-label tracking for the business's own customers.

---

## 8. Open decisions (recommendations to confirm)

1. **Wallet vs credit for the pilot** — *recommend credit-first (exists) + prepaid-per-batch; full wallet
   ledger deferred to P4.*
2. **KYC provider** — *recommend Sandbox (sandbox.co.in) behind `KycPort`, sandbox creds now.*
3. **Aadhaar scope** — Aadhaar OTP adds identity assurance but also compliance weight; *recommend GSTIN +
   PAN + bank for the pilot, Aadhaar OTP behind a flag.*
4. **Where invoices/remittance live** — new tables in `orders`, or a dedicated billing seam. *Recommend
   `orders` for the pilot to avoid a new module.*
