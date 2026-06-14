# M2 — Pricing API

Base path: `/api/v1/pricing`. JSON is **snake_case** (project-wide Jackson setting). Money is in
**paise**. Under the `!prod` demo profile `/api/**` is permitAll and the synthetic principal is ADMIN,
so admin endpoints work in the demo; in prod they require a JWT with the ADMIN role.

---

## POST /api/v1/pricing/quote — quote preview (open)

Runs the exact computation M4 uses at booking. Use it to show a price before the customer commits.

> **Idempotency-Key required.** This is a `POST /api/**`, so M4's global `IdempotencyFilter`
> intercepts it — send a unique `Idempotency-Key` header or you get `400 IDEMPOTENCY_KEY_REQUIRED`.
> The demo UI's `orderApi()` adds one automatically.

**Request** (`QuoteRequest`):
```json
{
  "customer_type": "B2C",
  "delivery_type": "INTERCITY",
  "origin_city": "BLR",
  "dest_city": "DEL",
  "chargeable_weight_grams": 1200,
  "declared_value_paise": 1000000,
  "b2b_rate_card_id": null,
  "payment_mode": "PREPAID"
}
```

**Response 200** (`QuoteResult`) — BLR→DEL base ₹157, 1.2 kg → 3 slabs (100+90+80 = 270%):
```json
{
  "base_amount_paise": 42390,
  "tax_paise": 7630,
  "total_price_paise": 50020,
  "breakdown": { "base_freight": 42390, "gst_18pct": 7630 },
  "rate_card_version": "B2C-PUBLISHED v1.0"
}
```

With `"payment_mode": "COD"` and `declared_value_paise: 1000000`, the breakdown gains
`"cod_charge": 15000` (1.5% of ₹10,000 > ₹50 min) and tax/total rise accordingly.

**422** when no rate is configured for the pair (e.g. any pair involving MAA):
```json
{ "title": "No rate configured", "status": 422, "detail": "No rate configured for MAA→DEL on card B2C-PUBLISHED" }
```
The same `NoRateConfiguredException` raised during an M4 **booking / cart-add / checkout** is mapped by
M4's `OrdersGlobalExceptionHandler` to `422 "Route not priceable"` (see M2-PRICING-DESIGN §10).

---

## GET /api/v1/pricing/rate-card — published tariff (open)

The customer-facing rate chart: the active card's parameters + its full city-pair base-price matrix.
No auth (published rates). Query param `customer_type` = `B2C` (default), `C2C`, or `B2B`. For B2B it
returns the active account card and its `discount_bps`.

`GET /api/v1/pricing/rate-card?customer_type=B2C`
```json
{
  "code": "B2C-PUBLISHED",
  "customer_type": "B2C",
  "version": "v1.0",
  "currency": "INR",
  "slab_grams": 500,
  "first_slab_pct": 100,
  "slab_decrement_pct": 10,
  "slab_floor_pct": 60,
  "volumetric_divisor": 5000,
  "gst_bps": 1800,
  "cod_pct_bps": 150,
  "cod_min_paise": 5000,
  "same_city_base_price_paise": 5000,
  "discount_bps": 0,
  "rates": [ { "origin_city": "BLR", "dest_city": "DEL", "base_price_paise": 15700 }, "… 56 rows" ]
}
```
The demo UI renders this as the **"📋 View full rate chart"** modal (matrix + rules + a B2C/C2C/B2B
toggle), reachable from both the B2C and B2B booking forms.

---

## Admin — rate cards (ADMIN)

Base path `/api/v1/pricing/admin/cards`.

| Method | Path | Purpose |
|--------|------|---------|
| `GET`  | `/` | list all cards (`RateCardResponse[]`) |
| `GET`  | `/{id}` | one card |
| `GET`  | `/{id}/rates` | the card's city-pair rows (`CityPairRateResponse[]`) |
| `POST` | `/` | publish a new version (supersedes the prior ACTIVE published card of the same type) → **201** |

**POST body** (`NewRateCardRequest`) — `symmetric: true` mirrors each pair automatically:
```json
{
  "code": "B2C-PUBLISHED",
  "customer_type": "B2C",
  "version": "v1.1",
  "discount_bps": 0,
  "same_city_base_price_paise": 5000,
  "symmetric": true,
  "pairs": [
    { "origin_city": "BLR", "dest_city": "DEL", "base_price_paise": 16000 }
  ]
}
```
Slab/GST/COD parameters default from `pricing.*` config (the published-sheet values). **201** returns
the new ACTIVE `RateCardResponse`.

---

## GET /api/v1/pricing/cost-floor?city=BLR — internal cost floor (ADMIN)

```json
{
  "city": "BLR",
  "cost_floor_paise": 12286,
  "breakdown": { "da_pickup": 4286, "van": 2500, "hub": 1500, "airline": 4000 },
  "costing_version": "v1.0"
}
```
Internal figure for M5/M6 feasibility — never surface to customers.
