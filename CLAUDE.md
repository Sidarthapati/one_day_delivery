# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

An in-house one-day intercity parcel delivery platform (B2B + B2C) across 5 Indian cities. Full logistics chain: pickup DA → hub sortation → airline flight → hub sortation → delivery DA. No third-party last-mile carriers in v1.

Stack: **Java 21 + Spring Boot 3.2**, **Maven multi-module**, **PostgreSQL**, **Kafka**. Frontend (React) comes later.

Design docs in `docs/`: `PRD-ONE-DAY-DELIVERY.md`, `MODULES.md`, `PRD-DISCOVERY-QUESTIONNAIRE.md`, `M3-GRID-DESIGN.md`, `M3-ALGORITHM-DISCUSSION.md`, `M3-IMPLEMENTATION-PLAN.md`.

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
| `grid` | M3 — Uber H3 hex grid (WGS84 lat/lon); DA rebalancing; nightly replan | common |
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
- **DA utilisation ~70%:** 70% of the shift is order-engaged time (travel to each pickup + service at each pickup); 30% is idle/unproductive (hub wait, repositioning without an order). This is the cost floor in M3, M5, M6 — don't optimise purely for speed.
- **Cross-module imports:** only import another module's public service interface, never its internal classes.

## Implementation Status

| Module | Status |
|--------|--------|
| `common` | `BaseEntity` + `MutableBaseEntity` (@MappedSuperclass, UUID id + audit timestamps) — done |
| `grid` (M3) | **Phases 1–8 done.** Phase 9 (integration tests) is next. See `docs/M3/M3-IMPLEMENTATION-PLAN.md` for full phase plan. Grid is **Uber H3 hexagons in WGS84** (not rectangular tiles). M4 integration live: `GridServiceabilityAdapter` implements `common.port.ServiceabilityPort`; `GridService.serviceableAt(lat,lon)` + `GET /api/grid/serviceable-at?lat=&lon=` resolve any in-boundary coordinate to a hex (city, hexId, h3Index, serviceable). `GridSeeder` (app, `@Profile("!prod")`) seeds the 5 city grids on startup. **NOTE:** the app's own `application.yml` must carry `grid.cities` + `grid.h3.resolution` (the grid module's yaml is shadowed on the classpath). M3's demand-feedback inputs from M4 (`shipment_leg_events`, `inter_stop_travel` tables, `orders.tile_queue_depth` events) are read best-effort but **not yet produced** by M4 — M3 degrades to historical/bootstrap demand until the lifecycle (M5–M9) lands. |
| `orders` (M4) | PRs #1–#12 merged. Flyway migrations (V4_1–V4_12), JPA entities (Shipment, ShipmentStateHistory, PaymentTransaction, B2bAccount, IdempotencyKey, ShipmentRefCounter, PickupOtp, Address embedded), Spring Data repositories (including PickupOtpRepository with pessimistic-lock verify, B2bAccountRepository with findByIdForUpdate), service layer (ShipmentStateMachine, TransitionRegistry, TransitionRegistryConfigurer, TransitionContext, CustomerVisibleStateMapper, ShipmentRefService, DeliveryTypeResolver, PaymentPort, PickupOtpService, B2bBookingService), idempotency infrastructure (IdempotencyFilter, IdempotencyKeyPurgeJob, IdempotencyProperties, V4_10 fingerprint migration), OTP infrastructure (PickupOtpService/Impl with BCrypt(4), PickupOtpProperties, PickupOtpController, V4_11 migration), B2C booking endpoint (B2cShipmentController POST /api/v1/b2c/shipments, BookingService/BookingServiceImpl with PREPAID + COD path, BookingRequest DTO with paymentMode @NotNull, BookingResponse DTO with PaymentSummary.mode field, GlobalExceptionHandler, ResilienceConfig/Properties, InvalidBookingRequestException), V4_12 COD partial index, B2B booking endpoint (B2bShipmentController POST /api/v1/b2b/shipments, B2bBookingService/B2bBookingServiceImpl with 6-step flow: account check → serviceability CB/TL → weight → B2B pricing with account rateCardId → DB TX with SELECT FOR UPDATE credit check + Shipment persist paymentMode=null + outstandingBalance increment + state history → best-effort ETA, B2bBookingRequest DTO flat structure no paymentMode, BookingResponse.payment @JsonInclude NON_NULL omitted for B2B, GlobalExceptionHandler handlers for AccountNotFoundException→404/AccountInactiveException→409/CreditLimitExceededException→402) — all done.<br><br>**Post-PR-#12 work (M3 integration + payments + auth, all done):** **(1) Real M3 serviceability** — `ServiceabilityPort.check(ServiceabilityQuery)` now takes both pincodes + origin/dest lat/lon and returns `originTileId`, `destTileId`, `deliveryType`; coordinate-based hex resolution is primary, pincode-prefix is fallback. Stub deleted. M4 stores both `origin_tile_id` and `dest_tile_id` (V4_15) + delivery type. Migrations now run V4_1–V4_16. **(2) Map booking UI** — pickup/drop pins on a Leaflet India map are the primary input; city+pincode derived from pins; verdict from `/api/grid/serviceable-at` (reads snake_case `m3.city_code`/`m3.h3_index`). `Address` gained `latitude`/`longitude` (JSONB, no migration). **(3) Payment gateway (Razorpay)** — `RazorpayPaymentAdapter` implements `PaymentPort` (live SDK mode `payment_capture=true` + refund, or local mock ids; `verifySignature` always recomputes real HMAC-SHA256 via `RazorpaySignatures`, tampered sig → 402). `RazorpayProperties` (`razorpay.live` default false). New endpoints: `POST /api/v1/payments/order` (PaymentController → `BookingService.quote()`), `POST /api/v1/payments/mock/pay` (MockPaymentController, `@Profile("!prod")`). **NEVER commit Razorpay keys** — pass via `RAZORPAY_LIVE`/`RAZORPAY_KEY_ID`/`RAZORPAY_KEY_SECRET` env vars only. **(4) Real M1 auth/authz** — `X-User-Id` header removed; userId + role now come from `@AuthenticationPrincipal AuthUserDetails` (JWT in prod; `DemoAuthFilter` injects a fixed ADMIN principal under `!prod`). `Authz` helper (`api/Authz.java`) gates every endpoint: `requireUserId` (null→401), `requireRole` (ADMIN always allowed, else 403) — B2C: C2C_CUSTOMER/B2C_CUSTOMER; B2B: B2B_USER **and** must own the account (`B2bAccount.ownerUserId`, V4_16 → `AccountAccessException`→403); pickup-OTP: DELIVERY_ASSOCIATE (real DA userId recorded as transition actor). `OrdersJwtAuthIntegrationTest` exercises the real `JwtAuthenticationFilter` end-to-end. Docs: `docs/M4/API-DOCS.md` + `API-FLOW.md`.<br><br>**(5) Cancellation (PR #13) — done.** `DELETE /api/v1/b2c/shipments/{ref}` + `DELETE /api/v1/b2b/shipments/{ref}` (optional `?reason=`). Eligibility behind `CancellationPolicy` (`CancellationPolicyImpl`, BD-001 cutoffs — DA_PICKUP cancellable through `PICKED_UP`/`PICKUP_FAILED`, SELF_DROP through `AWAITING_SELF_DROP`; past cutoff → `409`). `CancellationServiceImpl`: lane guard (B2C caller can't cancel a B2B shipment → `404`), refund branch (PREPAID → `PaymentPort.initiateRefund` + `PaymentTransaction` refund fields, a Razorpay failure still cancels and flags `FAILED` per OD-7; COD → none; B2B → decrement `outstanding_balance_paise` via `findByIdForUpdate` + ownership check), `stateMachine.transition(CANCELLED)`, sets `cancelled_at`/`cancellation_reason`. **No migration** (columns pre-existed). This closes the M4 **outbound** gap: M4 now emits all three contracts on `oneday.shipments.events` — `ShipmentCreatedEvent`, `ShipmentStateChangedEvent`, and the rich `ShipmentCancelledEvent` (in-process `ShipmentCancelled` → AFTER_COMMIT in `ShipmentEventProducer`), so M5 (drop from DA queue) / M10 (close SLA) can react to cancellations. **(6)** `DaEventsConsumer` now generates a pickup OTP on `PICKUP_ASSIGNED` (best-effort; cleartext dispatch awaits the notification service). Full orders suite 1059 green (`CancellationPolicyImplTest`, `CancellationServiceImplTest` added). |
| All others | Not started |

## Local Dev Setup

### Databases — **default to the DEVELOPMENT (Render) DB**

There are two Postgres databases. **Unless told otherwise, connect to the development (Render) DB by default** — it's the shared DB that TablePlus's "DEVELOPMENT" connection and the deployed app use, so it's the source of truth for verifying real data/state.

- **DEVELOPMENT (default):** Render-hosted Postgres 16, Oregon — DB `oneday_cipv`, user `oneday`, `sslmode=require`. **Credentials are not committed**; source them from `.env` (`SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`). To run psql against it:
  ```bash
  set -a; source .env; set +a
  HOST=$(echo "$SPRING_DATASOURCE_URL" | sed -E 's#jdbc:postgresql://([^:/]+):.*#\1#')
  DB=$(echo "$SPRING_DATASOURCE_URL"   | sed -E 's#.*:[0-9]+/([^?]+).*#\1#')
  PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" PGSSLMODE=require \
    psql -h "$HOST" -p 5432 -U "$SPRING_DATASOURCE_USERNAME" -d "$DB" -c "SELECT current_database();"
  ```
  Caution: it's shared and remote — DML/DDL here affects everyone. Flyway DDL is best applied by booting the app against it (`SPRING_FLYWAY_TARGET=<version>` to scope) so checksums are recorded, not by raw `ALTER`.
- **LOCAL (offline/throwaway):** PostgreSQL 16 via Homebrew (`brew services start postgresql@16`) — DB `oneday`, user `oneday`, password `secret`, port 5432. `grid/src/main/resources/application.yml` points the app here by default; a locally-run app (`mvn spring-boot:run` without sourcing `.env`) writes here. Use for isolated/offline work.
- **GUI:** TablePlus (`/Applications/TablePlus.app`) — "DEVELOPMENT" → Render `oneday_cipv`; add a separate connection to localhost:5432 for the local DB.
- Migrations run automatically via Flyway on app startup; to run manually against local: `psql -U oneday -d oneday -f <migration.sql>`.
- **Run the M1+M4 demo:** build/run **must** use JDK 21 (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21`; system default JDK 25 is rejected by the enforcer), then `mvn spring-boot:run -pl app` → `http://localhost:8080/`. The 5 city H3 grids seed on startup so serviceability is live. Demo UI is `app/src/main/resources/static/index.html`; `spring-boot:run` serves static files from `app/target/classes/static/`, so hot-update via `cp app/src/main/resources/static/index.html app/target/classes/static/index.html` + hard-refresh (backend changes still need `mvn clean install -pl orders,app -am -DskipTests` + restart). PREPAID uses the mock gateway by default; real Razorpay with `RAZORPAY_LIVE=true RAZORPAY_KEY_ID=… RAZORPAY_KEY_SECRET=…`.

## Open Questions (block implementation of specific modules)

See `docs/PRD-ONE-DAY-DELIVERY.md §20` for full list. Key blockers:
- Grid vertex/tile rules (partially resolved — see `M3-GRID-DESIGN.md`): G1 (cron-vertex meaning), G2 (UTM vs WGS84), G4 (approval SLA) still open; G3 (1 DA : N tiles and M DAs : 1 tile) resolved in design doc
- DA assignment objective function (blocks M5) — A1–A4
- Barcode standard + hub overload tactics (blocks M7, M8) — H1–H3
- Airline AWB issuer + handover SLA in minutes (blocks M9) — L1–L3
- RTO attempt count + DA penalty workflow (blocks M11) — F1–F3
