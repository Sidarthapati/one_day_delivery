# M9 — Airline & Flight Integration · Godspeed Requirements

**Plan sources:** Annexure E.1 (airport landscape), Annexure F (air cargo capacity by lane, procurement path), §8.1 (air leg = the big cost), Annexure M (rate assumptions, sources S8–S10).
**Module status:** Not started. The air leg is the largest cost and the density lever — these requirements are central to the model.

## The air leg in the model
The air leg is "the big one" — the largest per-parcel cost, which "nearly halves as each air waybill fills from 50 kg to 100 kg" (§8.1). M9 turns flight capacity into bookable lane capacity, preserves AWB-level traceability for LiveTrack, and feeds the cost floor (M2).

## Features / requirements

- **R1 — Flight schedule sync, night window first (F.2).** Ingest schedules for the 90 lanes, prioritising the **8 PM–2 AM night cargo window** (discounted rates, FM5 injection). Belly capacity per lane runs <50% utilised (E.2/F) — capacity is not the constraint; M9 just needs accurate cutoffs.
- **R2 — Hub-level (not parcel-level) flight assignment.** Assign **flight bags / AWBs** to flights at the hub level (M7 builds the bag; M9 books it onto a flight). Density target 50→100 kg/AWB.
- **R3 — Procurement path (F.3):**
  1. Launch on **GSA arrangements** (better-than-retail, AWB-level traceability preserved).
  2. Migrate to **block-space agreements** as a lane earns committed capacity.
  3. **Reject freight-forwarder consolidation** — it strips the AWB control LiveTrack depends on.
  M9 must model carrier optionality (Air India + IndiGo + GSA — Annexure L) for rate resilience.
- **R4 — AWB-level traceability is mandatory (F.3, §8).** Every Godspeed flight bag = a tracked AWB; M9 records AWB number, flight, departure/arrival, and links it to parcels (M8 spine). No consolidation that hides the AWB.
- **R5 — Per-AWB cost inputs to M2 (M.1, S8).** Min charge per AWB **₹1,500**, AERA terminal handling **₹380/AWB**, lane-specific GCR slab rates (MIN/Q45/Q100/Q300/Q500/Q1000, S9). M9 feeds these to the cost floor so contribution-per-parcel falls with density.
- **R6 — Flight-bank discipline (Annexure L risk: SLA).** Track booked vs available; hold flight-bank cutoffs so a bag makes its wave's flight. Tie to M10 (SLA) and M7 (hold-vs-density).
- **R7 — GHA / airline read APIs.** Provide manifest viewing + handover acknowledgement to airline/GHA (read-only role, M1). Tender + handover timestamps feed M10's origin-airport leg.
- **R8 — Handover SLA in minutes (PRD L-series, open).** Define the airport handover cutoff per lane so M5/M6 deadlines are anchored to real tender times.

## Acceptance signals
- Given a lane + wave, M9 returns the next eligible flight, its cutoff, and current AWB fill.
- A flight bag is booked as a single traceable AWB; LiveTrack resolves parcel → AWB → live flight.
- Cost-floor inputs reflect ₹1,500 min + ₹380 AERA spread over the AWB's parcel count.

## Open questions / deltas
- Q-L1: AWB issuer (GSA vs airline) and who owns the AWB number lifecycle. Blocks M8 linkage (Q-B2).
- Q-L2: Handover SLA in minutes per lane (PRD open) — needed to anchor M5/M6/M4 promised-date math.
- Q-L3: Real-time flight status source for LiveTrack (airline API vs third-party flight feed).
