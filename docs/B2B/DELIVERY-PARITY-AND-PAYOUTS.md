# B2B portal — Delhivery-parity gaps & how COD payouts reach the bank

Status: **living doc**, written 2026-07-29 during the P3 → P4 transition.
Scope: (1) an honest gap list of Delhivery order/label/finance features we do **not** have, with a
build-now / defer call for each; (2) the concrete mechanism for how COD money actually leaves us and
lands in a merchant's bank account. Companion to [`COD-REMITTANCE-DESIGN.md`](./COD-REMITTANCE-DESIGN.md).

---

## 1. The core model difference

**Delhivery is a multi-channel OMS + 3PL aggregator.** It ingests e-commerce orders (Shopify /
Amazon / custom), manages the seller's SKUs and inventory, and prints goods labels/invoices — so its
create-order form is full of order/product/seller fields.

**We are the carrier.** A shipment is: sender · receiver · addresses · weight/dims · declared value ·
price · state. We deliberately model *parcels*, not *orders*. That means several Delhivery fields
either don't apply to us or are a larger "order layer" we have chosen not to build for the pilot.

---

## 2. Gap list (from the Delhivery bulk-upload fields + console screenshots)

Legend: **HAVE** ✓ · **GAP** ✗ · priority **P** (pilot-critical) / **N** (next) / **L** (later or N/A).

| Delhivery capability | Us | Pri | Notes |
|---|---|---|---|
| Sale Order / reference number | ✓ (B2B `purchase_order_ref`) | — | Single field; enough. |
| Payment mode + **COD amount** | ✓ | — | Built in P3 + this tail (single + bulk). |
| Customer name / phone / address | ✓ | — | Core of every booking. |
| Weight / dimensions / declared value | ✓ | — | Parcel-centric core. |
| Pin-drop / geocoded destination | ✓ | — | We arguably beat Delhivery here (map-first). |
| **Merchant bank account for COD payout** | ✓ (this tail) | P | See §3. Was the missing half of COD. |
| **Seller details on label/invoice** (name, GSTIN, address) | ✗ | N | Today the invoice shows *us*; a real shipper wants *their* brand + GST on the label. Small add. |
| **e-Way bill number** | ✗ | N | Legally required for goods > ₹50k moving interstate — which our parcels are. One field + validation. |
| **Saved pickup locations / warehouses** | ~ (address book) | N | Merchants ship from 1–2 fixed warehouses; re-pinning per order hurts at 50–100 rows. Promote the address book to named pickup locations. |
| **Product / line items** (SKU code, name, qty, unit price) | ✗ | L | The real "order layer". Buys label contents, returns-by-item, goods-value-from-items. Large; a positioning call (carrier vs OMS). Deferred — the merchant's own goods invoice travels in the box. |
| Discount type/value, tax class code | ✗ | L | Belongs with the line-item/order layer. |
| Billing address ≠ shipping address | ✗ | L | Minor invoice nicety. |
| Partial COD / "amount paid" | ✗ | L | Edge case. |
| Fragile flag, packaging type | ✗ | L | Nice-to-have. |
| **Sales channels** (Shopify/Amazon/Woo) | ✗ | L | OMS integration — out of scope for the pilot and our carrier positioning. |
| Transport mode (Surface/Express) | ✗ | N/A | We are one-day air only. |
| Draft-vs-manifest two-step | ✗ | L | Operational nicety. |
| Returns / reverse orders (NDR) | ✗ | L | Belongs to **M11** (exceptions/RTO), not the portal. |
| Wallet / recharge / auto-top-up from remittances | ✗ | L | **P4** (wallet). |
| Reports / disputes / credit & debit notes | ✗ | L | Post-pilot finance polish. |

**Bottom line:** nothing on the GAP list blocks the Sept-1 pilot. The **N** items (seller details,
e-way bill, saved pickup locations) are small and make us credibly "B2B"; do them as a focused pass.
The **L** items — chiefly the product/SKU order layer and channels — are a separate, consciously
deferred workstream, not a P4 blocker.

---

## 3. How COD payouts actually reach the merchant's bank

Typing an account number into a form does nothing on its own. Paying a merchant is **three**
distinct problems, and we model each behind a swappable port so the pilot needs no external provider.

### 3.1 Capture
A form: account number + IFSC + beneficiary name (+ optional bank name, payout-notification emails).
Stored on `b2b_accounts` (V4_26), account number **masked** on every read (only last 4 shown).
Endpoints: `GET/PUT /api/v1/cod/bank-account` (B2B owner). UI: **Bank account** page in the portal.

### 3.2 Verify — the "connect using something" part
We never trust typed digits. Verification is a **penny-drop** via a payouts provider:

> The provider deposits **₹1** into the account and the bank returns the **registered
> account-holder name**, which we match against the merchant's KYC (business/PAN name).

That is the actual connection — an HTTPS call to the provider with our provider API key, and the ₹1
deposit is the proof the account exists and belongs to them. On **RazorpayX** the object chain is:

```
Contact (the merchant)
  └─ Fund Account (bank_account: account_number + ifsc)
       └─ Fund Account Validation   ← the ₹1 penny-drop
```

Validation is asynchronous: creating it returns immediately; the bank's name + `active|invalid`
result arrives on the `fund_account.validation.completed` **webhook**, which flips the account from
`PENDING` → `VERIFIED` / `FAILED`. Only a `VERIFIED` / `MANUAL_VERIFIED` account may be paid.

### 3.3 Pay — moving the money
Two options, same `PayoutPort.createPayout`:
- **Automated (RazorpayX Payouts):** `POST /payouts` (mode IMPS / NEFT / UPI) from our RazorpayX
  balance to the merchant's fund account → returns a payout id, then the real bank **UTR** on the
  `payout.processed` webhook.
- **Manual (pilot default):** finance does a NEFT from the company bank's netbanking and types the
  UTR back into the admin console — exactly what `markPaid(utr)` already does.

### 3.4 The port (mirror of `PaymentPort` on the collection side)

`PayoutPort` — `orders/service/PayoutPort.java`:
- `verifyBankAccount(account, legalName) → VerificationOutcome(state, providerRef, message)`
- `createPayout(request) → PayoutResult(settled, utr, providerRef, message)`

| Adapter | When | Verify | Pay |
|---|---|---|---|
| `ManualPayoutAdapter` (**default**, `payout.provider=manual`) | pilot, no provider | IFSC-format + account sanity → `MANUAL_VERIFIED` (finance eyeball) | no-op; admin records the real UTR |
| `RazorpayXPayoutAdapter` (`payout.provider=razorpayx`) | production | real penny-drop (Contact→FundAccount→Validation) → `PENDING`, webhook finalises | real `POST /payouts` |

Config `PayoutProperties` (`payout.*`): `provider`, `razorpayx-key-id/secret`, `razorpayx-account-number`,
`mode`. **Secrets via env only, never committed** (same rule as Razorpay collection keys).

### 3.5 Where it's gated in the ledger
- `createRemittance` → **409** unless the vendor has a `VERIFIED`/`MANUAL_VERIFIED` bank account
  on file. You cannot batch a payout into thin air.
- `markPaid(utr)` → manual confirm (pilot + demo path, unchanged).
- `payout()` (`POST /api/v1/admin/cod/remittances/{id}/payout`) → provider path: calls
  `PayoutPort.createPayout`; on a settled payout marks PAID with the provider UTR, else 409 asking
  the admin to record the UTR manually.

---

## 4. What this tail shipped (2026-07-29)

- **Bank account:** `b2b_accounts` payout columns (V4_26, demo account pre-verified); `PayoutPort` +
  `ManualPayoutAdapter` + `RazorpayXPayoutAdapter` + `PayoutProperties`; `BankAccountService`;
  `GET/PUT /api/v1/cod/bank-account`; `createRemittance` bank-account gate; admin `.../payout`
  endpoint. Portal **Bank account** page + a "set up bank account" banner on **COD remittances**.
- **Bulk COD:** optional `payment_mode` + `cod_amount_inr` columns in the bulk template (looked up by
  name, so old templates still validate); COD threaded through the cart (`cart_item` V4_27 →
  `AddCartItemRequest` → `CartItem` → B2B checkout `B2bBookingRequest`).

## 5. Recommended next (post-P4-decision)

1. **Seller details on label/invoice** (name, GSTIN, address) — small, high credibility.
2. **e-Way bill** capture + >₹50k validation — small, legal.
3. **Saved pickup locations** (named warehouses) — medium, big bulk-UX win.
4. **RazorpayX go-live**: onboard RazorpayX, add the `fund_account.validation.completed` +
   `payout.processed` webhook handlers, flip `payout.provider=razorpayx`.
5. **Product/SKU order layer** — only if we decide to be an OMS, not just a carrier. Large; separate epic.
