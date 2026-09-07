# Discussion-3 (ix) — Complete COD settlement (hub → bank-confirmed): scope

_Branch `feat/cod-bank-settlement`. Verified against current code (not just the split doc) via two code traces._

## Current state (verified end to end)

The COD money chain today, and where each step is **system-verified** vs **self-declared**:

| Step | Table / code | Trust |
|------|-------------|-------|
| Cash collected on delivery | `cod_collection` (`V4_25`), `CodCollectionState: AWAITING_COLLECTION → COLLECTED → REMITTED`; flipped by `CodCollectionListener` off the shipment state machine | ✅ system-verified |
| DA declares a deposit | `cod_cash_deposit` (`V4_33`), status `DEPOSITED → RECONCILED \| DISCREPANCY`; `DaCodController POST /deposits`; posts `−amount` to the DA ledger | ⚠️ **self-declared** |
| Admin "reconciles" | `AdminCodController PATCH /cash/deposits/{id}` → a **boolean the admin eyeballs** (`CodCashServiceImpl.reconcile`); no amount match, no bank feed; posts no ledger correction | ⚠️ **manual eyeball** |
| Per-DA cash ledger | `da_cod_balance` + append-only `da_cod_ledger` (`V4_50`), `CodLedgerService.post` (locked balance + signed entry) | ✅ authoritative |
| Remittance to vendor | `cod_remittance` (`V4_25`), `PENDING → PAID`; `PayoutPort` (Manual / RazorpayX); gated **only** on the *vendor's* bank being verified | outbound only |

**The three real gaps (exactly the CEO ask):**
1. **No verification at the cash handoff** — the DA deposit is a self-declared amount; "RECONCILED" is an admin boolean, not a verified receipt.
2. **No "money is in OUR bank account" concept anywhere** — every `bank`/`utr`/`verified` field is about the *outbound* transfer or the *vendor's* destination account. Inbound COD cash is never confirmed as landed.
3. **The deposit chain and the remittance pool are disconnected** — `findRemittable` returns COLLECTED collections regardless of whether the buyer's cash ever reached our bank. We can pay a vendor before the money lands.

## Proposed build (v1)

A **verified custody lifecycle** on the deposit, a **bank-confirmation** terminal state, and a **remittance gate** on it.

### 1. Verified custody lifecycle (replaces bare self-declaration)
Extend `cod_cash_deposit` from `DEPOSITED → RECONCILED` to a real handoff chain:

```
DECLARED ──(verified handoff)──▶ HANDED_OVER ──(bank slip)──▶ BANK_DEPOSITED ──(finance confirms credit)──▶ BANK_CONFIRMED
   │                                                                                                             
   └────────────────────────── DISCREPANCY (amount mismatch at any verified step) ◀───────────────────────────
```
- **Verified handoff (DA → station):** reuse the hashed-OTP pattern (`PickupOtpService` — BCrypt(4), single-use, `findBy…WithLock` pessimistic verify) so the station cashier's receipt of the cash is proven, not claimed.
- **BANK_DEPOSITED:** station records the bank deposit slip ref (`bank_deposit_ref`).
- **BANK_CONFIRMED (terminal):** finance confirms the credit actually landed (`bank_credit_ref` + `confirmed_at`).

### 2. Link cash to collections (FIFO settlement) + gate remittance
- `cod_collection` gains a `settlement_state: IN_CUSTODY → BANK_SETTLED`.
- When a deposit reaches **BANK_CONFIRMED** for amount X by DA D, allocate X to D's **oldest IN_CUSTODY collections (FIFO)**, flipping them `BANK_SETTLED` until X is exhausted. Cash is fungible, so FIFO is the correct, auditable allocation — no per-collection declaration needed from the DA.
- `CodRemittanceService.findRemittable` additionally requires `settlement_state = BANK_SETTLED` — **so remittance/refund only fire after the money is in our bank.**

### Reuse (do not rebuild)
- **Hashed-OTP handoff:** clone the `PickupOtp` trio (entity all-`updatable=false` + `BCryptPasswordEncoder(4)` + `findBy…WithLock`).
- **Locked append-only ledger:** `CodLedgerService.post` pattern (`ensureRow` + `findByIdForUpdate` + signed entry) for any new money-movement row; new rows extend `BaseEntity`, all `updatable=false`.
- **Idempotency:** `/api/v1/**` POSTs go through `IdempotencyFilter` (needs `Idempotency-Key`); physical events dedupe on a `(scope, ref)` partial-unique index (like `cod_cash_deposit`).
- **Bank config already exists:** `PayoutPort`, `PayoutProperties` (RazorpayX creds, env-only), `BankAccountService`. Next free migration: **`V4_51`** (note: `feat/b2b-member-budget` #202 also claims `V4_51` — if that merges first, this becomes `V4_52`).

## Decisions (resolved)
- **v1 = the full chain** including the FIFO remittance gate — the complete CEO ask.
- **Handoff = hashed OTP** (reuse the `PickupOtp` trio) — the station cashier's receipt is proven, not claimed.
- **Bank confirm = RazorpayX/bank webhook**, built now behind config (seam-stubbed like D2's WhatsApp): the webhook handler + signature verify flips a deposit to `BANK_CONFIRMED`. A **manual finance confirm endpoint** stays as the fallback/ops path until go-live. **Go-live (real creds, public webhook URL, signing secret) is tracked as a GitHub issue**, not built here.
- **Migration = `V4_52`** — `feat/b2b-member-budget` #202 already claims `V4_51` on its (unmerged) branch, so this takes the next number to avoid a Flyway collision at merge.

## Coordination
Sid **consults on the DA cash-custody leg** (the DA→station handoff + who operates the station cashier role) — advisory, per the split doc; not a blocking handoff. Loop him in at the handoff-verification design.

## Status
- **Built (branch `feat/cod-bank-settlement`):** `V4_52` (deposit custody columns, `cash_handoff_otp`, collection `settlement_state` + FIFO index, existing rows grandfathered); deposit state machine DEPOSITED→HANDED_OVER→BANK_DEPOSITED→BANK_CONFIRMED; hashed-OTP handoff (`CashHandoffOtpService`); cash-in-hand ledger deduction moved from declaration to verified handoff; FIFO settlement of collections on bank-confirm; `findRemittable` gated on BANK_SETTLED; station/finance endpoints on `AdminCodController`; the RazorpayX webhook (`/webhooks/cod/bank-credit`, HMAC-verified, disabled until a secret is set). **199 orders unit tests green** (5 new/changed for the chain + FIFO); `V4_52` applies + Hibernate-validates against a real Postgres.
- **Webhook go-live** (real creds, public URL, exact provider event shape): **issue #203**, not built here.
- **Pre-existing e2e red** (unrelated): the orders e2e context can't wire `auth.UserService` — same failure on main with this branch stashed.

## Verification (end to end)
Collected cash moves DA → station (OTP-verified receipt) → bank (slip ref) → **BANK_CONFIRMED** (finance) → the covered collections flip `BANK_SETTLED` FIFO → **only then** can a vendor remittance draw them. A deposit that fails an amount check goes `DISCREPANCY` and its collections stay `IN_CUSTODY` (unremittable).
