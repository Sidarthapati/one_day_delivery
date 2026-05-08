# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

An in-house one-day intercity parcel delivery platform (B2B + B2C) across 5 Indian cities. Full logistics chain: pickup DA → hub sortation → airline flight → hub sortation → delivery DA. No third-party last-mile carriers in v1.

Stack: **Java 21 + Spring Boot 3.2**, **Maven multi-module**, **PostgreSQL**, **Kafka**. Frontend (React) comes later.

Design docs in `docs/`: `PRD-ONE-DAY-DELIVERY.md`, `MODULES.md`, `PRD-DISCOVERY-QUESTIONNAIRE.md`, `M3-GRID-DESIGN.md`.

## Build Commands

```bash
mvn clean install          # build all modules
mvn clean install -pl auth # build a single module
mvn test -pl orders        # test a single module
```

The only runnable artifact is `app/` — it assembles all modules into one Spring Boot JAR.

## Project Structure

Maven multi-module monolith. Each directory is a Maven submodule with its own `pom.xml`:

| Directory | Module | Depends on |
|-----------|--------|------------|
| `common` | Shared infra: `BaseEntity`, Kafka topics, shared event POJOs | — |
| `auth` | M1 — Identity, JWT, 10 actor roles | common |
| `pricing` | M2 — Quote computation (volumetric weight, city-pair, B2B/B2C) | common |
| `grid` | M3 — Rectangular tile grid; DA rebalancing; nightly replan | common |
| `barcode` | M8 — Parcel ID generation; append-only scan ledger | common |
| `orders` | M4 — Shipment state machine (BOOKED → DELIVERED/RTO) | common, auth, pricing, grid, barcode |
| `dispatch` | M5 — DA priority queue; cron-meeting feasibility (hard constraint) | common, grid, orders |
| `routing` | M6 — Nightly van route plan over grid graph | common, grid |
| `hub` | M7 — Inbound dock, stand assignment, bag creation, manifests | common, barcode, orders |
| `airline` | M9 — Flight schedule sync, hub-level assignment, GHA API | common, hub, orders |
| `sla` | M10 — Per-leg SLA state (GREEN/AMBER/RED); Kafka consumer | common, orders, barcode |
| `exceptions` | M11 — Failure capture, call center queue, RTO workflow | common, orders, sla |
| `app` | Entry point only — wires all beans, no business logic | all modules |

**Within each module**, follow this package layout:
```
com.oneday.{module}/
  api/         REST controllers
  service/     Business logic (keep impl package-private; expose only the interface)
  domain/      JPA entities
  repository/  Spring Data repos
  events/      Kafka producers and consumers
  dto/         Request/response objects
```

## Key Design Invariants

- **Append-only audit trail:** scans, manifests, role changes, and grid/route overrides are never mutated.
- **Nightly stability:** grids (M3) and van routes (M6) replan once nightly. Intraday changes need station manager approval.
- **70/30 demand weighting:** grid sizing and flight assignment use 70% historical / 30% current demand.
- **Cron-meeting is a hard constraint:** M5 must confirm a parcel can reach the hub cron before airline cutoff — no assignment otherwise.
- **DA utilisation ~70%:** cost floor is first-class in M3, M5, M6 — don't optimise purely for speed.
- **Cross-module imports:** only import another module's public service interface, never its internal classes.

## Open Questions (block implementation of specific modules)

See `docs/PRD-ONE-DAY-DELIVERY.md §20` for full list. Key blockers:
- Grid vertex/tile rules (partially resolved — see `M3-GRID-DESIGN.md`): G1 (cron-vertex meaning), G2 (UTM vs WGS84), G4 (approval SLA) still open; G3 (1 DA : N tiles and M DAs : 1 tile) resolved in design doc
- DA assignment objective function (blocks M5) — A1–A4
- Barcode standard + hub overload tactics (blocks M7, M8) — H1–H3
- Airline AWB issuer + handover SLA in minutes (blocks M9) — L1–L3
- RTO attempt count + DA penalty workflow (blocks M11) — F1–F3
