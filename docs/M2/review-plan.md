# Review Plan — M2 Pricing & Costing Engine

A guide for reviewing the M2 implementation. Branch: `M2-Implementation`. Nothing is committed yet.

## What changed & why

The `pricing/` module was an empty shell (only `pom.xml`); quotes were served by a flat-rate
`StubPricingAdapter` in `app/`. This change implements the **full M2** — DB-backed versioned rate
cards driven by the supplied published rate sheet, the slab/COD/GST pricing engine, an internal
per-parcel cost-floor model, admin + preview APIs — and wires the real values into M4 (including a new
COD surcharge path). See `docs/M2/M2-PRICING-DESIGN.md` for the design and `docs/M2/M2-API-DOCS.md` for
the API.

## How to review (suggested order)

### 1. Contract changes in `common` (start here — defines the boundary)
- `common/.../port/dto/QuoteRequest.java` — **added `paymentMode`** (nullable) so M2 can apply the COD
  charge. This is the one breaking change to an existing contract; confirm the two M4 call sites below
  match.
- `common/.../port/CostingPort.java`, `dto/CostFloorQuery.java`, `dto/CostFloorResult.java` — **new**
  internal cost-floor contract for future M5/M6.

### 2. Pricing engine — the core logic (highest scrutiny)
- `pricing/.../service/PricingEngine.java` — pure math, no DB. Verify against the sheet:
  - slab count = `ceil(weight/500)`, min 1;
  - `pct(n) = max(floor, firstSlabPct − decrement·(n−1))` → 100, 90, 80, 70, 60, 60…;
  - `base_freight = Σ basePrice·pct(n)/100`;
  - B2B `discount_bps`; COD `max(₹50, 1.5%·declaredValue)`; GST 18% on (freight − discount + COD).
  - Cross-check with `pricing/.../service/PricingEngineTest.java` — includes the sheet's 4.8 kg → 7.0×
    worked example, COD min-vs-% branches, and the B2B discount.

### 3. Rate-card resolution & versioning
- `pricing/.../service/RateCardService.java` + `service/impl/RateCardServiceImpl.java` — card resolution
  (B2B by id, else active published per customer type), base-price lookup (same-city falls back to the
  card's `same_city_base_price_paise`), and **append-only** `publishNewVersion` (insert ACTIVE +
  supersede prior). Check the `@Transactional` boundaries and the supersede logic.
- `pricing/.../service/CityCodes.java` — normalises city tokens to IATA codes (M4 sends `BLR/DEL/...`).
- Tests: `service/impl/RateCardServiceImplTest.java`.

### 4. Costing model
- `pricing/.../service/CostingService.java` + `service/impl/CostingServiceImpl.java` — per-parcel floor
  divides shift cost by **effective** capacity (nameplate × ~70% utilisation). Internal/ADMIN-only.
- Test: `service/impl/CostingServiceImplTest.java`.

### 5. Adapter + APIs
- `pricing/.../adapter/PricingPortAdapter.java` — implements `common.PricingPort`; the bean M4 injects
  (no `@Profile`, so it's the single impl in all profiles now the stub is gone).
- `pricing/.../api/PricingController.java` — `POST /api/v1/pricing/quote` (open preview).
- `pricing/.../api/PricingAdminController.java` — rate-card CRUD, `@PreAuthorize("hasRole('ADMIN')")`.
- `pricing/.../api/CostingController.java` — `GET /api/v1/pricing/cost-floor` (ADMIN).
- `pricing/.../api/PricingExceptionHandler.java` — `NoRateConfiguredException` → 422, validation → 400
  (scoped to `com.oneday.pricing.api` so it doesn't collide with other modules' advices).
- DTOs: `dto/RateCardResponse.java`, `dto/CityPairRateResponse.java`, `dto/NewRateCardRequest.java`.
- Config: `config/PricingProperties.java` (sheet defaults, bound from `pricing.*`).

### 6. Domain & schema
- Entities: `domain/RateCard.java` (MutableBaseEntity), `domain/CityPairRate.java` (BaseEntity,
  append-only), `domain/CostingParams.java`. Repos under `repository/`.
- Migrations `pricing/src/main/resources/db/migration/pricing/`:
  - `V2_1__create_rate_card.sql` — header + all rate params; partial unique index = one ACTIVE
    published card per B2C/C2C.
  - `V2_2__create_city_pair_rate.sql` — `(card, origin, dest)` unique.
  - `V2_3__create_costing_params.sql` — one ACTIVE per city.
  - `V2_4__seed_rate_cards.sql` — **verify the matrix transcription** (₹→paise ×100, both directions),
    the B2C/C2C cards, and the demo B2B card id `c0000000-…-000000000001` (must match orders `V4_13`).
  - Check column types/nullability match the entities (`ddl-auto=validate` enforces this at boot).

### 7. M4 wiring & stub removal
- `orders/.../service/impl/BookingServiceImpl.java` — passes `req.getPaymentMode()` (B2C/C2C).
- `orders/.../service/impl/B2bBookingServiceImpl.java` — passes `null` (B2B is credit-billed, no COD).
- `app/.../stubs/StubPricingAdapter.java` — **deleted** (real adapter replaces it). Confirm no other
  prod-profile `PricingPort` bean exists and orders tests still use `@MockBean PricingPort`.
- `pricing/pom.xml` — added `data-jpa`, `validation`, `security`, `test`.

### 8. Docs
- `docs/M2/M2-PRICING-DESIGN.md`, `docs/M2/M2-API-DOCS.md`, `docs/DECISIONS.md` §M2 (M2-D-001…009),
  `CLAUDE.md` status row.

## Files changed

**New (pricing module, 25 files):** `pricing/src/main/java/com/oneday/pricing/**` (domain ×3, repository
×3, service ×6 incl. impls, adapter ×1, api ×4, config ×1, dto ×3) + `db/migration/pricing/V2_1–V2_4` +
3 test classes. **New (common, 3):** `CostingPort`, `CostFloorQuery`, `CostFloorResult`. **New (docs,
2):** `docs/M2/*`.

**Modified:** `common/.../QuoteRequest.java`, `orders/.../BookingServiceImpl.java`,
`orders/.../B2bBookingServiceImpl.java`, `pricing/pom.xml`, `docs/DECISIONS.md`, `CLAUDE.md`.
**Deleted:** `app/.../stubs/StubPricingAdapter.java`.

## How it was verified
- `mvn clean install` green; `mvn test -pl pricing` and the full `orders` suite pass (0 failures).
- Booted against the local DB: Flyway applied `V2_1–V2_4`, Hibernate `validate` passed. Live endpoint
  checks (port 8081):
  - PREPAID BLR→DEL 1.2 kg → `base_freight 42390`, GST `7630`, total `50020`.
  - COD (declared ₹10,000) → `cod_charge 15000`, GST recomputed on the larger base.
  - B2B demo card → `b2b_discount −6359` (15%), version `B2B-ACME-DEMO v1.0`.
  - MAA→DEL → `422 No rate configured`.
  - `GET /cost-floor?city=BLR` → `12286` (`da_pickup 4286 + van 2500 + hub 1500 + airline 4000`).

## Review focus / risk areas
1. **Matrix transcription in `V2_4`** — a wrong cell silently misprices a lane. Cross-check against the
   sheet image.
2. **`QuoteRequest` arity change** — ensure every construction site compiles (only the two M4 impls
   construct it; test mocks use `any()`).
3. **Rounding** — engine uses `Math.round` per slab and for discount/COD/GST; confirm this matches
   intended commercial rounding.
4. **Chennai (MAA) gap** — serviceable but unpriced → `422` by design (M2-D-009). Confirm this is the
   desired behaviour vs a fallback price.
5. **Single `PricingPort` bean** — adapter now active in all profiles incl. prod; the demo (`!prod`)
   relies on the seeded local/dev rows existing.

## Not done (out of scope / follow-ups)
- MAA and any non-sheet lanes are unpriced.
- Costing-params figures in `V2_4` are plausible placeholders, not sourced ops data.
- No commit/PR created yet.

---

## Addendum — follow-up changes (after the initial review plan)

These landed in response to demo feedback; review alongside the original set.

### A. Clean "route not priceable" UX (instead of a 500)
- `orders/.../api/OrdersGlobalExceptionHandler.java` — **new** `@ExceptionHandler(NoRateConfiguredException.class)`
  → `422 "Route not priceable"`, so booking / cart-add / checkout surface the lane clearly.
- `orders/.../config/ResilienceConfig.java` — pricing circuit breaker now `ignoreExceptions(NoRateConfiguredException.class)`
  so unpriced-lane attempts don't trip the breaker and 503 the priced lanes. **Review:** confirm the
  exception propagates unwrapped through `callWithTimeout` (the TimeLimiter rethrows the original).

### B. Customer-facing price preview + rate chart
- `pricing/.../api/PricingController.java` — **new** open `GET /api/v1/pricing/rate-card?customer_type=`
  (B2C/C2C/B2B) → `PublishedTariffResponse` (card params + full matrix + `discount_bps`). **Review:** the
  `@RequestParam(name = "customer_type")` must keep the explicit name — binding by the Java name silently
  returns B2C for every type (the bug that was caught in testing).
- `pricing/.../service/RateCardService.java` (+impl) — `activePublishedCard(CustomerType)`.
- `pricing/.../dto/PublishedTariffResponse.java` — **new**.
- Frontend (`app/.../static/`): live read-only **cost box** on the B2C form (`refreshQuote` driven from
  `renderLocCard` + input/`selectPay` handlers) and a **rate-chart modal** with a B2C/C2C/B2B toggle,
  reachable from B2C + B2B forms. CSS in `app.css`; assets cache-busted (`?v=m2-ratechart`).

### C. Tests
- `pricing/.../service/impl/PricingM4IntegrationTest.java` — **new**: real adapter→service→engine through
  `PricingPort` with M4-shaped requests (incl. M4's weight math) across all types + the 422 path.

### Verified (Singapore dev DB, port 8080)
- `rate-card` returns distinct cards: B2C-PUBLISHED (0%), C2C-PUBLISHED (0%), B2B-ACME-DEMO (15%).
- Booking MAA→DEL COD → `422 "Route not priceable"` (not 500). Priced HYD→DEL / BLR→DEL quote correctly.

### Deploy / env
- New Render **Singapore** DB `singapore1dd` = clone of Oregon + `V2_1–V2_4` applied; `.env` now defaults
  to Singapore (Oregon kept as a commented fallback). Oregon does **not** yet have `V2_x`.
