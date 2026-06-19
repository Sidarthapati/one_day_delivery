# M7 — Hub Operations & Sortation · Godspeed Requirements

**Plan sources:** §8 & §8.1 (two aggregation points, density = cost strategy), Annexure G (airport process timings), Annexure J (handling cost line, AWB fill), F (AWB economics).
**Module status:** Not started. These are greenfield requirements drawn straight from the plan.

## The hub's job in the model
There are exactly **two aggregation points** per parcel (§8). The hub is where parcels are consolidated into **flight bags / AWBs**, and the **entire cost strategy is filling those AWBs to 50→100 kg+** (§8.1, F). M7 is therefore the lever that turns volume into margin.

## Features / requirements

- **R1 — Two-aggregation discipline.** Origin hub: receive consolidation-van bags → sort by destination lane → build flight bags → tender to airline. Destination hub: receive ex-flight bags → break → sort → release to delivery vans. No third sort point.
- **R2 — Flight-bag / AWB build to density target (§8.1, F).** Sortation groups parcels by **destination lane** and packs AWBs toward **50 kg minimum, 100 kg+ target**, because fixed per-AWB charges (₹1,500 min, AERA ₹380, M.1/S8) spread across more parcels. M7 must expose **current kg/AWB per lane** so ops can hold a flight bag for the next wave when below threshold.
- **R3 — Airport process within budget (Annexure G).** Origin-airport leg (tender, screen, stage, load) budgeted **3.0 h** (consultant 2.25–3.25 h); destination-airport leg (unload, break, sort, DO, release) budgeted **2.0 h** (consultant 2.5–3.0 h). M7 timestamps each sub-step to feed M10 per-leg SLA.
- **R4 — Stand assignment & inbound dock.** Assign inbound consolidation vans / ex-flight ULDs to docks/stands; sequence by wave.
- **R5 — System manifests, append-only.** Every bag and manifest is an append-only record (platform invariant), forming part of the LiveTrack chain (bag → AWB).
- **R6 — Scan-driven (M8).** Every dock-in, sort, bag-add, bag-close, tender, break, release is a scan event → M8 ledger. Hub state is derived from scans, never hand-edited.
- **R7 — Light-node handling is lighter (E.3).** Light nodes have no full origin operation; M7's full inbound/outbound applies to the **6 full nodes**. Light nodes: destination break + handoff to 3rd-party last mile only.
- **R8 — Density hold policy.** A flight bag below the kg/AWB economic threshold may be **held to the next wave** if SLA budget allows — the tension between density (cost) and the 16h target (SLA). M7 owns this decision with M10 visibility.

## Acceptance signals
- A flight bag's live kg/AWB is queryable per lane; bags below threshold are flagged "hold-eligible" only when SLA buffer permits.
- Origin-airport sub-steps are timestamped and sum within ~3 h.
- A held-then-released bag never breaches the 16h internal target without an M10 amber/red.

## Open questions / deltas
- Q-H1: Barcode/AWB standard (PRD open H1) — blocks M7/M8/M9 contract on bag identity.
- Q-H2: Who owns the density-vs-SLA hold decision authority — Station Manager, a rule, or the control tower (M10)?
- Q-H3: Hub overload tactics (PRD H2/H3) — surge handling when a wave spikes.
