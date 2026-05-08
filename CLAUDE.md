# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

This is a **pre-implementation** repository. All current content is design documentation — no application code exists yet. The implementation phase follows resolution of open questions listed in `docs/PRD-ONE-DAY-DELIVERY.md §20`.

## What This Is

An in-house one-day intercity parcel delivery platform (B2B + B2C) operating across 5 Indian cities. The system owns the full logistics chain: pickup DA → hub sortation → airline flight → hub sortation → delivery DA. No third-party last-mile carriers in v1.

Key design docs:
- `docs/PRD-ONE-DAY-DELIVERY.md` — business rules, SLAs, actor roles, non-goals, open questions
- `docs/MODULES.md` — 11-module architecture, event topology, cross-module dependencies, recommended parallel build tracks
- `docs/PRD-DISCOVERY-QUESTIONNAIRE.md` — stakeholder interview notes, many fields still TBD

## Architecture

**Modular monolith** designed for future microservice extraction. The 11 modules and their integration style:

| Module | Role | Integration |
|--------|------|-------------|
| M1 — Auth & Role Management | Identity, sessions, permissions for 10+ actor types | Middleware/library |
| M2 — Pricing & Costing Engine | Quote computation (volumetric weight, city-pair, B2B/B2C tiers) | Direct function call from M4 |
| M3 — Serviceability & Grid Management | Rectangular tile grid over urban area; dynamic DA rebalancing | HTTP (M4 calls at booking); admin UI for nightly replan |
| M4 — Order Booking & Shipment Lifecycle | Canonical state machine: BOOKED → DELIVERED / RTO | Core API; publishes state-change events to Kafka |
| M5 — DA Assignment & Dispatch | Real-time priority queue per DA; cron-meeting feasibility is a hard constraint | Background worker; consumes M4 events |
| M6 — Van Routing & Scheduling | Nightly hub-consolidation route plan over grid graph | Scheduled batch job; reads M3 grid |
| M7 — Hub Operations & Sortation | Inbound dock, stand assignment, bag creation, system manifests | Domain service + operator UI |
| M8 — Barcode, Label & Scanning | Unique parcel ID; append-only scan ledger | Event store + utility; all scans publish to Kafka |
| M9 — Airline & Flight Integration | Flight schedule sync, hub-level flight assignment, GHA API tracking | Integration adapter; publishes flight events |
| M10 — SLA Monitoring & Escalation | Per-leg real-time SLA state (GREEN/AMBER/RED); supervisor escalation | Kafka consumer; streams all scan + state events |
| M11 — Exception Handling & RTO | Failure capture, call center queue, rescheduling, RTO logic | Workflow orchestrator; consumes M10 alerts |

**Event bus:** Kafka for all async fan-out (scan events, state transitions, SLA triggers, flight status changes). Direct function calls only for synchronous booking-time operations (M4→M2, M4→M3).

## Key Design Invariants

- **Append-only audit trail** (NFR-1): all material scans, manifests, role changes, and grid/route overrides are logged and never mutated.
- **Nightly stability:** van routes (M6) and service grids (M3) replan once nightly. Intraday changes require station manager approval and are logged.
- **70/30 demand weighting:** grid sizing and flight assignment use 70% historical / 30% current-day demand — applies in both M3 and M9.
- **Cron-meeting as hard constraint:** DA assignment (M5) must verify the parcel can reach the hub cron before the airline cutoff; no assignment is made if infeasible.
- **DA utilisation target ~70%:** cost floor per parcel is a first-class constraint in M3, M5, and M6 — do not optimise purely for speed.
- **Symmetric origin/destination design:** M5 and M3 are shared between pickup and delivery legs; the delivery side mirrors the pickup side.

## Recommended Build Order

From `docs/MODULES.md` — build in parallel tracks to avoid blocking:

1. **Track A:** M1 (Auth) + M2 (Pricing) — pure logic, no external deps
2. **Track B:** M3 (Grid) + M8 (Barcode) — foundational data structures
3. **Track C:** M4 (Order Lifecycle) — integrates Tracks A & B
4. **Track D:** M5 (DA Assignment) + M6 (Van Routing) — depend on M3
5. **Track E:** M7 (Hub Ops) + M9 (Airline) — can start with mocked scan/flight events
6. **Track F:** M10 (SLA Monitor) + M11 (Exceptions) — last; consumes all; design Kafka schema early

## Open Questions (block implementation)

Before writing code for certain modules, these must be resolved with stakeholders (see PRD §20):

- **Cities:** Which 5 cities, pilot order (§2.1)
- **Grid rules:** Exact vertex definition, tile merging/splitting policy (G1–G4)
- **DA assignment:** Objective function definition, "closer" meaning, replan input freeze time (A1–A4)
- **Airline:** AWB issuer, liability matrix, hub-to-airline SLA in minutes (L1–L3)
- **Hub:** Barcode standard, stand reassignment (reprint vs electronic), overload tactics (H1–H3)
- **RTO:** Attempt count/days, DA penalty workflow, call center hours (F1–F3)
