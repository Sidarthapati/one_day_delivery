# Discussion-4 (CASH) — Driver cash ledger: scope

_Branch TBD. Under D3 (ix) COD-settlement; Sid consults the DA cash-custody leg. Verified against main (post-#204) + oneday-driver-app + oneday-web on 2026-09-13._

## Reality check — the D4 gap list predates #204
My ix custody chain (#204) already closed several of the D4 doc's "gaps". Current truth:

**Already built (ix / #204):**
- Deposit state machine `DEPOSITED → HANDED_OVER → BANK_DEPOSITED → BANK_CONFIRMED` (+ DISCREPANCY).
- **Station verification at handoff** via a hashed single-use OTP (`verifyHandoff`) — the DA→station handoff is *proven*, not self-declared; cash-in-hand deducts only at verified handoff.
- FIFO settlement of collections on bank-confirm; city-scoped station/admin endpoints; the `da_cod_balance` + append-only `da_cod_ledger` + DA `/cod/da/summary` and `/ledger` reads.

**Genuinely still open (this feature):**
| # | Gap | Where | Effort |
|---|-----|-------|--------|
| G1 | **Independent station count** — station enters the *counted* amount at handoff and it's checked against the DA's declared amount (variance → DISCREPANCY). Today the station confirms receipt (OTP) but re-uses the DA's own figure — no independent number. **Priority slice** (₹7 L Aug reconciliation). | backend | S–M |
| G2 | **₹5,000 cash-in-hand ceiling** — nothing gates it today; a DA can hold unlimited cash. Force or warn a deposit before more work. | backend (+ DA app surface) | S–M |
| G3 | **60-day trail + CSV export** — the ledger read is page/size only (no date bounds); no CSV anywhere for deposits/ledger. | backend | S–M |
| G4 | **Driver-app cash screen** — the DA app has **zero** backend cash integration; cash-in-hand is derived locally from the task list (resets each shift, not a real balance). Need: real cash-in-hand from `/cod/da/summary`, a **declare-deposit** flow (shows the handoff OTP), and the 60-day trail. Greenfield. | driver-app | M–L |
| G5 | **Station custody-chain UI** — the web station DA-cash console has the read views + legacy reconcile, but **no buttons** for handoff / bank-deposited / bank-confirmed / the new station count. | oneday-web (station) | M |

## Proposed phasing (each a shippable PR)
- **Phase 1 · G1 Independent station count** ✅ **DONE** (branch `feat/cod-station-count`) — `verifyHandoff` now takes `countedAmountPaise`; `V4_58` stores it beside the DA's declared `amount_paise`; an **exact mismatch → `DISCREPANCY`** and posts **no** cash-in-hand deduction (nothing moves until resolved), else HANDED_OVER + deduction as before. `countedAmountPaise` surfaced on `CodCashDepositResponse`. Orders unit tests **239 green** (new: count-mismatch flags discrepancy + no ledger post). Resolution of a flagged discrepancy (recount/adjust) is a follow-up. Decisions: exact match (no tolerance); block (no partial handoff).
- **Phase 2 · G2 ceiling + G3 trail/export** *(backend)* — a configurable cash-in-hand ceiling (`cod.cash.ceiling-paise`, default ₹5,000) surfaced on `daSummary` (`over_ceiling`, `must_deposit`) and enforced where the DA takes work; a date-range ledger query (`from`/`to`, default last 60 days) + a CSV export endpoint for the ledger/deposits (reuse the shipments CSV pattern).
- **Phase 3 · G4 Driver-app cash screen** *(driver-app)* — a Cash screen (Shift tab): real cash-in-hand, a "Deposit cash" flow that calls `POST /cod/da/deposits` and shows the handoff code for the station, and a 60-day trail (date filter + share/export). Replaces the local `CashOnHand` derivation with the backend balance.
- **Phase 4 · G5 Station custody UI** *(oneday-web)* — buttons on the station DA-cash deposit view for the full chain incl. the station count entry (Phase 1).

## Key decisions (before Phase 1)
1. **Station-count mismatch behaviour** — DA declares ₹X, station counts ₹Y ≠ ₹X: block as `DISCREPANCY` (no handoff until resolved), or record the *station's* counted figure as authoritative and flag a variance but proceed?
2. **Ceiling behaviour** — a **hard block** (DA can't be assigned / accept more COD work until they deposit) or a **warning** (surfaced, non-blocking)? Fixed ₹5,000 or per-city configurable?
3. **Lead phase** — start with the **priority fraud slice (G1)**, or the **most-visible DA-app cash screen (G4)** for demo value?

## Reuse
- Custody chain, OTP, FIFO, ledger — all from ix; extend, don't rebuild.
- CSV: `AdminOrdersController.toCsv` pattern (shipments) → reuse for the ledger/deposits export.
- Driver-app: `src/api.ts` (`daId + token` call convention), `ShiftStack` (add a `Cash` screen), `Screen`/`Card`/`Money`/`useAsync` primitives.
- Next migration: **V4_58** (V4_57 is head).
