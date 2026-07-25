# M10 — Implementation Plan

> How to use this plan: M10 shipped in **one pass** on `f-m10-design` (not phased PRs). The work is
> recorded as ordered items W1–W6 so it stays followable; they were built together. Design:
> `docs/M10/M10-SLA-DESIGN.md`. Decisions: `docs/DECISIONS.md` (M10-D-001…007).

## Status: built ✅ (branch `f-m10-design`, uncommitted)
- 9 unit tests green (`mvn test -pl sla`); `common` + `sla` + full `app` assemble.
- Self-contained (M10-D-007): only additive `common` event contracts + app config touched; no other
  module's logic changed.

---

## W1 — Docs ✅
`M10-SLA-DESIGN.md`, this plan, and decisions M10-D-005/006/007 (+ the `oneday.sla.events` exchange)
in `docs/DECISIONS.md`.

## W2 — Schema + domain ✅
### What was built
- Flyway `sla/src/main/resources/db/migration/sla/V10_1__create_sla.sql`: `sla_shipment` + `sla_leg`
  (mutable), `sla_escalation` + `sla_action` (append-only). Version 10.1 sorts after the existing
  top-level `V10`; `flyway.out-of-order=true`.
- `common` enums `SlaState`, `SlaLegType`, `EscalationLevel` (referenced by the event payloads, so
  they live in `common`). Module enum `SlaActionType`.
- Entities `SlaShipment`/`SlaLeg` (extend `MutableBaseEntity`), `SlaEscalation`/`SlaAction`
  (standalone, `created_at` only). Repos with the control-tower / pass-rate / idempotency queries.
- `SlaProperties` (`@ConfigurationProperties("sla")`) + `sla:` block in the **app** `application.yml`
  (budgets Σ=810m=13.5h under the 16h target — the 2.5h cushion is the AMBER band).
### Verify
`ddl-auto: validate` passes on boot (entity columns match `V10_1`).

## W3 — Ingestion + leg lifecycle ✅
### What was built
- `SlaMessagingTopology` — `sla.{shipments,hub,cron,flight,da,exceptions}` queues (+ the produced
  `oneday.sla.events` exchange), each via `RabbitStreamSupport` (DLX/DLQ).
- Consumers taking the exact concrete/sealed types the producers stamp: `SlaShipmentEventsConsumer`
  (`BaseShipmentEvent`), `SlaHubEventsConsumer` (`HubEventPayload`), `SlaCronEventsConsumer`
  (`CronEventPayload`), `SlaDaEventsConsumer` (`DaLifecycleEvent`), `SlaExceptionsEventsConsumer`
  (`ExceptionsEvent`), `SlaFlightEventsConsumer` (`@RabbitHandler`, `autoStartup=false` until M9).
- `SlaLifecycleService` — opens the SLA + leg plan on CREATED (M10-D-006), advances/closes legs on
  STATE_CHANGED (only-advance, idempotent), closes on terminal/cancel, marks exceptions breached.
### Verify
Booking a shipment writes one `sla_shipment` + N `sla_leg` rows; a state change advances the legs.

## W4 — Projection + derivation + sweeper ✅
### What was built
- `ProjectionCalculator` (pure, M10-D-005) + `SlaEngine.recompute` (writes leg colours + rollup,
  upgrades to BREACHED once the target passes, fires escalations on entering RED/BREACHED).
- `SlaLegCatalog` (budgets, per-`DeliveryType` plan, state→leg map, terminal/exception classification).
- Enrichment from parcel-keyed events (last-mile deadline, flight cutoff, loop overflow).
- `SlaSweeper` (`@Scheduled`, `sla.sweeper-fixed-delay-ms`) — catches silent overruns.
### Verify
`ProjectionCalculatorTest` covers GREEN, AMBER-not-RED, cushion→RED, historical AMBER, enrichment.

## W5 — Escalation ✅
### What was built
- `EscalationService` — append-only raise (RED→STATION_MANAGER / BREACHED→ADMIN, custody city),
  idempotent per `(shipment, leg, colour)`; `SlaEventProducer` publishes `SlaEscalationRaisedEvent`
  / `SlaBreachedEvent` on `oneday.sla.events`.
- `common`: `EventStreams.SLA_EVENTS`, `SlaEventType`, `events/sla/*`,
  `NotificationEventType.SLA_ESCALATION`.
### Verify
Forcing a projection past 16h writes one `sla_escalation` + emits `SLA_ESCALATION_RAISED`.

## W6 — Control-tower API ✅
### What was built
- `SlaDashboardController` (`/api/v1/sla`) + `sla.api.Authz` (role gate + city scope mirroring
  `AdminOrdersController`) + `SlaQueryService`/impl + record DTOs.
- Endpoints: `control-tower`, `shipments/{ref}`, `red-queue`, `metrics/pass-rate`,
  `escalations/{id}/ack`, `escalations/{id}/act` (append-only `sla_action`; `sla:red:action` roles).
### Verify
ADMIN sees all cities; STATION_MANAGER sees only their `cityId` (403 if unassigned); `act` appends
an `sla_action`.

---

## Build sequence & blockers
- **Sequence:** W1 → W2 → W3 → W4 → W5 → W6 (done together on `f-m10-design`).
- **Out of this pass:** web control-tower page (`oneday-web`); cross-module `slaDeadline` back-fill.
- **Open blockers (don't block v1):** Q-S3 (3rd-party light-node legs); M10-D-003 (hub→airport minute
  threshold); van/flight/bag→parcel attribution for richer enrichment.

## End-to-end verification
1. `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`; `mvn clean install -pl common,sla,app -am -DskipTests`.
2. `mvn test -pl sla` (9 green).
3. `mvn spring-boot:run -pl app` (staging DB via `.env`); flyway applies `V10_1`.
4. Book DEL→BOM → `GET /api/v1/sla/shipments/{ref}` shows all-GREEN legs, anchors booked+16h/+24h.
5. Drive to `PICKED_UP` late / push `VAN_RUNNING_LATE`; force projection past 16h → leg RED,
   `sla_escalation` row, `oneday.sla.events` (check `rabbitmqadmin -N cloudamqp` / logs).
6. Hit `control-tower` as ADMIN (all) vs STATION_MANAGER (own city, 403 if none); `act` appends.
