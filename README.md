# Godspeed — One-Day Delivery

**Godspeed** is an in-house intercity parcel delivery platform that guarantees **next-day delivery
across 5 Indian cities** — a parcel picked up today arrives at its destination city tomorrow.
It serves both businesses (**Godspeed for Business**) and individual customers, and owns the entire
logistics chain end to end, with no third-party last-mile carriers in v1:

```text
Pickup DA  →  Origin hub sortation  →  Airline flight  →  Destination hub sortation  →  Delivery DA
```

Every leg is time-boxed against that one-day promise: the platform will only book a shipment it can
actually get onto a flight in time to land the next day — the **cron-meeting constraint** below is
what makes the guarantee real rather than aspirational.

## Why it's interesting

- **One-day SLA as a hard constraint, not a hope.** Dispatch refuses any pickup that can't reach the
  hub before the airline cutoff, so the delivery date is committed at booking time.
- **A living map of the cities.** Serviceability, demand, and delivery-associate (DA) territories run
  on an **Uber H3 hex grid** that replans nightly — the operational footprint adapts to real demand.
- **Full custody chain.** Every parcel is tracked scan-by-scan (pickup → hub → van → flight → hub →
  doorstep) on an append-only ledger, so location and accountability are always answerable.
- **Per-leg SLA monitoring** (GREEN / AMBER / RED) with an event-driven control tower that escalates
  before a breach, not after.

## Stack

- **Java 21 + Spring Boot 3.2**, Maven multi-module monolith
- **PostgreSQL** (Flyway migrations), **RabbitMQ** (CloudAMQP) for the event bus
- React consoles (customer / business / hub / station / airline / admin) live in the separate
  `oneday-web` repo; the driver app in `oneday-driver-app`

## Modules

The system is a Maven multi-module monolith. Each module is a submodule with its own `pom.xml`;
`app/` is the only runnable artifact and wires every bean into one Spring Boot JAR.

| Module | Responsibility |
|--------|----------------|
| `common` | Shared infra: `BaseEntity`, event POJOs, cross-module ports |
| `auth` (M1) | Identity, JWT, 10 actor roles |
| `pricing` (M2) | Quote computation — volumetric weight, city-pair, B2B/B2C |
| `grid` (M3) | Uber H3 hex grid; DA rebalancing; nightly replan |
| `barcode` (M8) | Parcel ID generation; append-only scan ledger |
| `orders` (M4) | Shipment state machine (BOOKED → DELIVERED/RTO) |
| `dispatch` (M5) | DA priority queue; cron-meeting feasibility |
| `routing` (M6) | Nightly van route plan over the grid graph |
| `hub` (M7) | Inbound dock, stand assignment, bag creation, manifests |
| `airline` (M9) | Flight schedule sync, hub-level assignment, GHA API |
| `sla` (M10) | Per-leg SLA state (GREEN/AMBER/RED); RabbitMQ consumer |
| `exceptions` (M11) | Failure capture, call-center queue, RTO workflow |
| `app` | Entry point — wires all modules, no business logic |

## Build & Run

Requires **JDK 21** (the enforcer rejects newer JDKs).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

mvn clean install            # build all modules
mvn clean install -pl auth   # build a single module
mvn test -pl orders          # test a single module

mvn spring-boot:run -pl app  # run the app at http://localhost:8080/
```

Flyway applies migrations automatically on startup, and the 5-city H3 grids seed on boot
(non-prod profiles), so serviceability is live immediately.

## Key design invariants

- **Append-only audit trail** — scans, manifests, role changes, and grid/route overrides are
  never mutated.
- **Nightly stability** — grids (M3) and van routes (M6) replan once nightly; intraday changes
  need station-manager approval.
- **Cron-meeting is a hard constraint** — M5 confirms a parcel can reach the hub cron before the
  airline cutoff, or it is not assigned. This is what underwrites the one-day guarantee.
- **Cross-module imports** — only import another module's public service interface, never its
  internal classes.

## Documentation

Design docs live in `docs/` (per-module `PRD`, design, and implementation-plan files).
Contributor and architecture guidance is in [`CLAUDE.md`](./CLAUDE.md).
