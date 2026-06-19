# Godspeed — Module-Wise Features & Requirements

> **Source document:** *Godspeed — The Case for 24-Hour India · Business Plan & Investment Memorandum* (Knostics Infodel Pvt Ltd, June 2026), incl. Annexures A–M.
> **Purpose:** translate the commercial/operational plan into engineering requirements, mapped onto the existing M1–M11 module structure (`docs/MODULES.md`). Each requirement is traceable to a plan section so product and engineering share one vocabulary.

This is **not** a re-statement of the technical design docs (those live in `docs/M1…M6`). It is the *business-driven requirements layer* — "what the plan promises the market" → "which module must deliver it."

---

## The product in one paragraph

Godspeed sells **guaranteed 24-hour intercity parcel delivery** (run to a **16-hour internal target**, an 8-hour engineered buffer) to D2C merchants across a **10-city / 90-lane** air mesh, point-to-point with **two aggregation points** (origin hub → night flight → destination hub). The merchant turns it on at their own checkout (Shopify/Woo plugin), the buyer self-selects speed at a **~₹150** price, the parcel is visible end-to-end via **LiveTrack** (barcode → AWB → flight → rider), and COD is paid **directly to the merchant's QR** at the door. The ground network already runs (Knostics, 90+ locations, 45k parcels/day); the build is the commercial + tracking software on top.

---

## Traceability matrix — plan feature → owning module(s)

| Plan feature (section) | Primary module | Supporting |
|---|---|---|
| Actor roles, merchant accounts, plugin API keys, SFP seller (§5, A.3, B.1) | **M1** | — |
| ₹150 pricing tiers, customer-choice premium, credit-as-privilege, COD ₹7, RTO billing, AWB/AERA cost floor (§9, H, K) | **M2** | M7, M9 |
| 10-city network, full vs light nodes, serviceability (E.2–E.4) | **M3** | M6 |
| Customer-choice checkout, ETA promise, wallet debit, COD-QR, booking sources (§2, §5, §7) | **M4** | M2, Merchant Platform |
| DA pickup/delivery assignment, five pickup waves, cron-to-van (G.1) | **M5** | M3, M6 |
| Consolidation van milk-run, wave alignment, point-to-point (§8, G.1) | **M6** | M3, M9 |
| Two-aggregation hub ops, flight-bag fill to 50→100 kg/AWB, airport process (§8.1, G, J) | **M7** | M8, M9 |
| Parcel identity + scan ledger as the LiveTrack spine (§8, B.1) | **M8** | LiveTrack |
| Flight schedule, GSA→block-space, AWB traceability, belly capacity, min ₹1,500/AWB (E.1, F, M) | **M9** | M2, M7 |
| Per-leg SLA budgets, 16h/24h, control tower, 99% SLA (§8, G, D, L) | **M10** | M9, M5 |
| RTO (revenue-positive, −30–33%), reattempt wave LM5, WISMO reduction (§5, B.2, G.1) | **M11** | M4, LiveTrack |
| Shopify/Woo plugin, prepaid wallet & settlement, merchant dashboard, SFP (§7, I, K) | **Merchant Platform** *(new)* | M1, M2, M4 |
| LiveTrack — real-time flight-level customer tracking (§5, §8, B.1) | **LiveTrack** *(new)* | M8, M9, M5, M4 |

---

## Files in this set

| File | Module | Plan promise it underwrites |
|---|---|---|
| [M1-AUTH.md](M1-AUTH.md) | Auth & roles | Who can act — merchants, DAs, ops, plugin keys, SFP sellers |
| [M2-PRICING.md](M2-PRICING.md) | Pricing & costing | ₹150 that wins on price *and* speed; density-driven cost floor |
| [M3-GRID.md](M3-GRID.md) | Serviceability & grid | The 10-city map; full vs light nodes |
| [M4-ORDERS.md](M4-ORDERS.md) | Booking & lifecycle | Customer-choice checkout; the 24h promise as state |
| [M5-DISPATCH.md](M5-DISPATCH.md) | DA dispatch | Pickups/deliveries hit the flight waves |
| [M6-ROUTING.md](M6-ROUTING.md) | Van routing | Consolidation milk-run feeding the cron |
| [M7-HUB.md](M7-HUB.md) | Hub ops | Two aggregation points; fill the AWB |
| [M8-BARCODE.md](M8-BARCODE.md) | Barcode & scans | The visibility spine |
| [M9-AIRLINE.md](M9-AIRLINE.md) | Airline & flight | The air leg — biggest cost, the density lever |
| [M10-SLA.md](M10-SLA.md) | SLA & escalation | 16h internal / 24h public, engineered buffer |
| [M11-EXCEPTIONS.md](M11-EXCEPTIONS.md) | Exceptions & RTO | Turning failures revenue-positive |
| [MERCHANT-PLATFORM.md](MERCHANT-PLATFORM.md) | *Cross-cutting (new)* | The afternoon switch-on; wallet; SFP |
| [LIVETRACK.md](LIVETRACK.md) | *Cross-cutting (new)* | The one piece built deeply |

---

## Global numbers every module should hold (from the plan)

| Parameter | Value | Source |
|---|---|---|
| Public delivery promise | **24 h** | §8 |
| Internal ready-to-delivered target | **16 h** (8 h buffer) | §8, G |
| Base price | **₹150** | H |
| Avg chargeable weight | **0.5 kg** | M.1 |
| Volumetric divisor | **5000** | M.1 |
| COD-to-merchant charge | **flat ₹7** (~₹2 handling) | §5, B.1 |
| Min charge per AWB | **₹1,500** | M.1 |
| Airport terminal handling | **AERA ₹380/AWB min** | S8, M.1 |
| Target SLA / monthly churn | **99% / ~2.5%** | D, L |
| Network | **10 cities, 90 directional lanes, 6 full + 4 light nodes** | E.2 |
| Night flight window | **8 PM – 2 AM** | F.2 |
| Pickup waves FM1–5 | 8 AM, 11 AM, 2 PM, 6 PM, 12 AM | G.1 |
| Delivery waves LM1–5 | 8 AM, 10 AM, 1 PM, 4 PM, 7 PM | G.1 |
| AWB density target | **50 kg → 100 kg+** per AWB | §8.1, F |

> ⚠️ The current codebase (CLAUDE.md) lists **5** grid cities; the plan specifies **10** (6 full + 4 light). This is a known scope delta — see [M3-GRID.md](M3-GRID.md).
