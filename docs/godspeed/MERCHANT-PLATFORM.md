# Merchant Platform · Godspeed Requirements (cross-cutting / new surface)

**Plan sources:** §7 (how Godspeed wins demand), Annexure I (channel economics), Annexure K (investment, prepaid wallet), §5/B.1 (offering: COD-QR, customer-choice, no warehousing), A.3 (SFP upside).
**Module status:** **Not an existing M1–M11 module.** The plan's *entire demand engine* lives here — this is the most important net-new build the business plan introduces. It sits on top of M1 (auth/keys), M2 (pricing), M4 (booking), and M9/M8 (tracking).

## Why this is its own surface
"The easiest [way to sell speed] is to let a D2C brand that owns its own checkout switch Godspeed on in an afternoon" (§7). The operations are inherited; **demand is the thing to build**, and the plan makes the merchant's own checkout carry it. None of M1–M11 owns the merchant-facing storefront integration, wallet, or dashboard — yet the plan's revenue depends entirely on them.

## Features / requirements

- **R1 — Storefront plugins (Shopify, WooCommerce) (§7, I).** A merchant installs a plugin and a **paid 24-hour option appears at checkout** in an afternoon, near-zero touch. CAC target ~₹500 self-serve vs ~₹7,900 field (Annexure I). The plugin authenticates as the merchant via an API key (M1 R2) and calls M4 booking + M9 serviceability.
- **R2 — Customer-choice checkout widget (§2, §5).** Render at the cart: "free in 4 days" vs "guaranteed-by-tomorrow for ~₹35". Show a **definite delivery date** (the conversion lever, B.2), computed via M4 promised-date (wave + flight aware).
- **R3 — Prepaid wallet & settlement (Annexure K).** Merchants **prepay into a wallet**; Godspeed finances no receivables ("the cash cycle never runs against the company", §9). The platform owns wallet top-up, balance, per-shipment debit (M4 R4), and **weekly settlement at scale** (K.1). Funding need is set by *settlement discipline*, not losses — so the wallet is a core financial-control feature.
- **R4 — Earned credit terms (H.1).** Support prepaid (base), 7-day (+₹5–10), 15-day (+₹15), 30-day (exception) credit tiers as a merchant-account attribute feeding M2 pricing.
- **R5 — Direct COD-to-merchant via QR (§5, B.1).** Customer pays the **merchant's own QR at the doorstep**; funds reach the merchant directly, no carrier float, flat ₹7. The platform provisions/links the merchant QR and reconciles doorstep settlements (DA app surfaces it, M5 R6).
- **R6 — Merchant dashboard.** Shipments, live LiveTrack links, wallet ledger, SLA/RTO/WISMO KPIs (the "measurably better business" the plan sells, §5), and reporting. This is the surface that proves the P&L impact to the merchant.
- **R7 — Aggregator channel (spillover) (§7).** Integrate aggregator platforms as *flight-fill* spillover — "useful to fill a flight, never the channel that owns the customer." Lower priority than owned plugins; must not capture the customer relationship.
- **R8 — Seller-Fulfilled Prime onboarding (A.3, upside).** Let a marketplace seller use Godspeed as carrier to qualify for SFP, keeping the Prime badge while escaping the fulfilment-fee stack. Held as upside / Phase 2; needs marketplace API integration.
- **R9 — Two-CAC funnel instrumentation (I).** Track field vs self-serve acquisition cost; blended CAC falls as plugins carry more (~₹5,000 @ 40% self-serve, falling). The platform must attribute each merchant to a channel.

## Acceptance signals
- A merchant installs the Shopify plugin and completes a live test shipment without a human touch.
- Checkout shows a concrete date and a ~₹35 express upsell; the buyer's choice flows to M4 `service_level`.
- A booking debits the wallet atomically; weekly settlement reconciles wallet ↔ shipments ↔ COD-QR receipts.
- Merchant dashboard reports conversion-relevant KPIs (SLA pass-rate, RTO rate, WISMO rate) per the plan's value proof (B.2).

## Open questions / deltas
- Q-MP1: Is the Merchant Platform a **new top-level module** (e.g., M12) or a sub-system spanning M1/M2/M4? Recommend a dedicated module — it's the demand engine.
- Q-MP2: Wallet ledger system of record — inside M2 (pricing/billing) or a standalone finance service?
- Q-MP3: QR provider for COD-to-merchant (UPI VPA per merchant?) and reconciliation source of truth.
- Q-MP4: Plugin distribution/review (Shopify App Store listing) timeline — affects self-serve ramp (Annexure D).
