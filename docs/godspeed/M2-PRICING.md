# M2 — Pricing & Costing · Godspeed Requirements

**Plan sources:** §9 (what Godspeed earns), Annexure H (pricing & revenue logic), Annexure J (cost build-up), Annexure K (settlement), Annexure M (assumptions), §5/B.1 (COD, customer-choice).
**Module status:** M2 **done** (versioned rate cards, slab math, COD, GST). These requirements *re-point the rate card at the Godspeed price book* and add the density-driven cost floor.

## The pricing thesis
"₹150 sits below Blue Dart Air's ₹189–440 while delivering 3–18× faster" (H). The premium product is also the cheaper one — so pricing is simple and public-facing, while **cost** falls with density (the air leg halves as an AWB fills 50→100 kg, §8.1). M2 owns both the price book and the internal cost floor.

## Features / requirements — price book (Annexure H)

| Tier | Price | When | Rule |
|---|---|---|---|
| **24H Standard** | ₹150 | default premium product | base rate |
| **Late cut-off / Priority** | ₹175–200 | later handover, higher urgency wave | surcharge band |
| **Dense-lane scale** | ₹125–140 | high-volume merchant once lane density allows | volume discount, gated on lane |
| **Thin / special destination** | ₹175–225+ | low-density lane / special handling | surcharge band |

- **R1 — Customer-choice mechanic.** Buyer pays **~₹35** to upgrade standard→express; this drops merchant break-even AOV from ~₹1,400 to ~₹700 (H, B.1). M2 must express the buyer-facing premium independently of the merchant's base contract.
- **R2 — Credit priced as a privilege (H.1).** Settlement terms are a price input:
  - Prepaid wallet → base rate (lowest)
  - 7-day earned credit → base + ₹5–10
  - 15-day credit → base + ₹15
  - 30-day credit → exception only, base + ₹25 or anchor terms + deposit
- **R3 — COD-to-merchant pricing.** Flat **₹7** charge, ~₹2 handling cost (§5, B.1). Distinct from legacy COD float — the carrier holds no cash. (Existing M2 COD = `max(₹50, 1.5% GMV)` is the *legacy* B2C model and must be **superseded** by the flat ₹7 Godspeed model for this product line.)
- **R4 — RTO billed at standard return rates** → revenue-positive, not a loss leader (H, B.1). M2 must produce an RTO charge line for M11.
- **R5 — Weight model.** Avg chargeable weight 0.5 kg; volumetric divisor **5000** (M.1) — already M2's divisor. Confirm slab decay still fits the ₹150 anchor.

## Features / requirements — internal cost floor (Annexure J, §8.1)
- **R6 — Three-cost density model.** Per-parcel cost = first mile + **air leg (the big one)** + last mile + handling/tech/overhead. Air leg cost = `fixed per-AWB charges ÷ parcels on AWB`, so it falls as the AWB fills.
- **R7 — Per-AWB fixed charges.** Min charge per AWB **₹1,500**; AERA terminal handling **₹380/AWB** (M.1, S8). These are the spread-able fixed costs that make density the entire cost strategy (§8.1).
- **R8 — Cost floor feeds feasibility, never the customer** (consistent with `CostingPort`, ADMIN-only). Used by M5/M6/M9 to decide lane/AWB economics.
- **R9 — Contribution turns positive with volume, not price** (J): the costing model must report contribution-per-parcel by lane so ops can see when a lane crosses ~100 kg/AWB.

## Acceptance signals
- A ₹1,500-order express quote shows buyer premium ≈ ₹35 and merchant base ≈ ₹150.
- Same merchant on prepaid vs 15-day credit differs by ₹15.
- Cost-floor API returns a *falling* air-leg per-parcel cost as simulated AWB weight rises 50→100 kg.

## Open questions / deltas
- **Δ COD model conflict:** legacy `max(₹50,1.5%GMV)` vs plan's flat ₹7 — needs a product-line discriminator on the rate card.
- Q-P1: Are dense-lane (₹125–140) discounts merchant-negotiated (B2B card) or automatic once a lane crosses a density threshold?
- Q-P2: GST treatment of the buyer-paid premium vs merchant base — who is the taxable supplier of the speed upgrade?
