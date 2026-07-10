# M4 — Booking & Shipment Lifecycle · Godspeed Requirements

**Plan sources:** §2 (what solving looks like), §5 (merchant offering), §7 (channels), B.1 (offering), H (customer-choice mechanic), K (prepaid wallet).
**Module status:** M4 **extensive** (B2C/B2B booking, state machine BOOKED→DELIVERED/RTO, payments, cancellation, idempotency). These requirements add the **customer-choice checkout**, **wallet-funded booking**, and **multi-source order origination**.

## What the plan promises at the cart
"At checkout she sees a choice: free delivery in four days, or guaranteed-by-tomorrow for a small premium" (§2). The booking module must turn that *choice* into a shipment carrying a hard 24h promise (16h internal), funded from the merchant's prepaid wallet, originated from any of three channels.

## Features / requirements

- **R1 — Customer-choice service level on the order.** Each shipment carries `service_level ∈ {STANDARD, EXPRESS_24H}`. EXPRESS_24H is the Godspeed product; STANDARD may fall back to a non-air path (out of scope for the air mesh). The buyer self-selects; the ~₹35 premium (M2 R1) attaches to the order.
- **R2 — Definite delivery date, not a range (B.2).** The order must compute and persist a **promised delivery datetime** (24h public), and an internal **target datetime** (16h). "Replace vague 5–7 days with a definite date" is the conversion lever — the date is a contract, surfaced at checkout and on LiveTrack.
- **R3 — Three order-origination channels (§7):**
  1. **Own storefront plugin** (Shopify/Woo) — primary, via Merchant Platform API key.
  2. **Aggregator spillover** — fills flights, never owns the customer.
  3. **Marketplace seller-fulfilled (SFP)** — upside; same booking endpoint, `source=SFP`.
  All converge on the existing B2B/B2C booking flow.
- **R4 — Wallet-funded booking (K).** Merchants prepay into a wallet; booking **debits the wallet** (no carrier-financed receivables). Booking must fail closed if wallet balance < charge (unless merchant has earned credit term, M2 R2). Replaces/complements the Razorpay PREPAID path for merchant-originated orders.
- **R5 — COD-to-merchant flag (§5).** Order may be COD with **direct-to-merchant QR** settlement at the door (flat ₹7). M4 records payment mode; the QR/settlement mechanics live in Merchant Platform + M5 drop-completed.
- **R6 — Centralised fulfilment (§5).** Single origin (merchant's metro hub city) → any served destination; no per-city inventory. Booking takes one origin address and any of the 10 destinations.
- **R7 — RTO as a first-class terminal branch** (already in M4 state machine) — but RTO must be **billable & revenue-positive** (M2 R4) and minimised by prompt verified delivery (B.2: −30–33% RTO). Feed M11.
- **R8 — Returns are a product, not a failure** (B.1 "revenue-positive returns") — a return shipment is a normal booking on the reverse lane.

## Acceptance signals
- An EXPRESS_24H order shows a concrete promised date (now + ≤24h) and an internal target (now + ≤16h).
- A plugin-originated order debits the merchant wallet atomically; insufficient balance → 402, no shipment.
- A COD order records `cod_to_merchant=true` and a settlement reference, not a carrier-held float.
- An SFP-sourced order books through the same endpoint with `source=SFP`.

## Open questions / deltas
- **Δ Wallet vs Razorpay:** merchant-originated orders should debit wallet; how does this coexist with the existing buyer-paid Razorpay flow for direct B2C?
- Q-O1: Is STANDARD (4-day) even in scope for the air platform, or only EXPRESS_24H? (Plan sells the choice but Godspeed only *operates* the express path.)
- Q-O2: Promised-date computation must consult wave cutoffs (G.1) + flight schedule (M9) at booking time — synchronous dependency on M9.
