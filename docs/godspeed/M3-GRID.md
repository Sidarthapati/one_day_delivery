# M3 — Serviceability & Grid · Godspeed Requirements

**Plan sources:** Annexure E (Phase 1 operating network — E.1 airport landscape, E.2 network at a glance, E.3 the cities, E.4 why this footprint), A.2 (SAM — metro-origin shares), §5 (no regional warehousing).
**Module status:** M3 **Phases 1–8 done** (H3 hex grid, serviceability port, 5 seeded cities). **Key delta:** plan needs **10** cities with two node tiers.

## The network the grid must encode (E.2–E.3)

Phase 1 = **point-to-point mesh, 10 cities, 90 directional lanes**, split into:

| Node type | Cities | Ground model | Grid implication |
|---|---|---|---|
| **Full node (6)** | Delhi, Mumbai, Bengaluru, Hyderabad, Kolkata, Chennai | own ground op, dedicated daily handling, both origin & destination | **full H3 grid + DA tile assignment** (pickup *and* delivery) |
| **Light node (4)** | Ahmedabad, Pune, Jaipur, Lucknow | delivery-focused, lighter handling, **3rd-party / on-demand last mile** | **delivery serviceability only**; no own-DA pickup grid required |

## Features / requirements

- **R1 — Ten serviceable cities, not five.** Extend the seeded grid set from the current 5 to the 10 Phase-1 cities. (CLAUDE.md currently seeds 5; this is the headline gap.)
- **R2 — Node tiering as a first-class attribute.** Each city carries `node_type ∈ {FULL, LIGHT}`. Full nodes get the complete pickup+delivery hex grid + DA assignment; light nodes get **delivery serviceability** and route to a third-party last-mile path (no own-DA cron grid).
- **R3 — Metro-origin serviceability.** Pickups originate in metro/full nodes (A.2: e-comm metro-origin 55%, D2C 55%). Booking serviceability must confirm origin ∈ full node, destination ∈ any of the 10.
- **R4 — Lane = ordered (origin, destination) pair.** 90 directional lanes (10×9). The grid module is the authority on "is this lane served?" — consumed by M4 at booking and M9 for flight mapping.
- **R5 — Centralised fulfilment support (§5).** "One metro hub reaches every served city" → serviceability must let a merchant ship *nationally from a single origin*, so the grid never requires per-destination merchant warehousing.
- **R6 — Cron-vertex co-design unchanged.** Full-node grids must still place hub-consolidation van vertices (feeds M5/M6). Light nodes have no cron grid.
- **R7 — Expansion gated, not scheduled (Annexure L).** New nodes added only on lane density + SLA pass-rate. Grid config must be data-driven (add a node without code change).

## Acceptance signals
- `serviceable-at` returns a verdict for all 10 cities; light-node coordinates resolve as `deliverable` but not `pickup-grid` tiles.
- A Mumbai→Lucknow booking is serviceable (full origin, light destination); a Lucknow→Jaipur booking (light→light) is flagged per policy.
- Adding an 11th city is a config/seed change, not a schema change.

## Open questions / deltas
- **Δ 5 → 10 cities** — confirm IATA/boundary/pincode data for the 5 new cities (HYD, CCU already partly; add AMD, PNQ, JAI, LKO + ensure MAA priced — see M2 gap on Chennai).
- Q-G1: Do light nodes need *any* hex grid (for 3rd-party last-mile zoning), or just pincode-level serviceability?
- Q-G2: Lane-level enable/disable (thin-lane delay, Annexure L risk register) — does M3 own the lane on/off switch or M9?
