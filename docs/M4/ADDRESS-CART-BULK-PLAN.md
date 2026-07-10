# Address Book + Shipment Cart + Excel Bulk Booking — Implementation Plan

Status: **approved, not yet implemented** · Owner: M4 (orders) · Created 2026-06-08

Adds a Swiggy-style address-capture flow, a per-user saved-address book, a shipment
**cart** (each line is an independent shipment), and **Excel bulk upload** that validates
rows, adds valid ones to the cart, and reports invalid ones with reasons.

All new code lands in the **orders (M4)** module — it already owns `Address`, `Shipment`,
the booking flow (`BookingService`), and depends on `auth` for the user FK.

---

## 1. Confirmed decisions

| # | Decision |
|---|----------|
| Lanes | Both **B2C** and **B2B**. Address book + map capture for everyone; cart + Excel for both (Excel is the B2B headline). |
| Cart item | **Independent shipment** — each line carries its own pickup + drop + parcel. Checkout books them all. |
| Build | Backend APIs **+** the existing static demo UI (Leaflet), end-to-end testable. |
| **Payment / COD** | **COD removed from the UI entirely.** B2C cart → one **Razorpay prepaid** order for the cart total. B2B cart → **debit against the credit account** (`B2bAccount.credit_limit` / `outstanding_balance_paise`, `SELECT FOR UPDATE`) — the industry-standard B2B model (Shiprocket / Delhivery One / DTDC business accounts). No gateway for B2B. |
| Excel failures | Valid rows are added to the cart; invalid rows surface as a **dismissable error modal** (closes only on the ✕) listing each failed row + field + reason. |
| Partial checkout | Book every valid item; failures stay in the cart with their reason (not all-or-nothing). |
| Stale prices | Cart caches a quote per item; re-validated at checkout (nightly grid replan can move prices) — mismatches surface **before** payment. |
| `save_as` name | **Optional** (nullable) — a user may save an address without naming it. |
| Door/building photos | **Out of scope for v1** (no file-upload/storage work). |

---

## 2. Data model & migrations (orders, next free versions `V4_18`–`V4_20`)

`Address` (the embeddable in `orders/domain/Address.java`) gains three **optional** fields,
persisted inside the existing JSONB blob (no column migration for the shipment use):
`house_floor`, `building_street`, `area_locality`.

### `V4_18__create_saved_address.sql`
```sql
CREATE TABLE saved_address (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL REFERENCES users(id),
    label                 VARCHAR(10) NOT NULL,          -- HOME | OFFICE | OTHER
    save_as               VARCHAR(100),                  -- optional free text ("Mom's place")
    contact_name          VARCHAR(100),
    contact_phone         VARCHAR(15),                   -- +91XXXXXXXXXX
    -- address (mirrors the embedded Address)
    house_floor           VARCHAR(200),
    building_street       VARCHAR(200),
    area_locality         VARCHAR(300),
    line1                 VARCHAR(200) NOT NULL,
    line2                 VARCHAR(200),
    city                  VARCHAR(100) NOT NULL,
    pincode               VARCHAR(10)  NOT NULL,
    state                 VARCHAR(100) NOT NULL,
    landmark              VARCHAR(200),
    latitude              DOUBLE PRECISION,
    longitude             DOUBLE PRECISION,
    delivery_instructions VARCHAR(500),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_saved_address_user ON saved_address(user_id);
```

### `V4_19__create_cart.sql`
```sql
CREATE TABLE cart (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id),
    status     VARCHAR(16) NOT NULL DEFAULT 'OPEN',      -- OPEN | CHECKED_OUT | ABANDONED
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- at most one OPEN cart per user
CREATE UNIQUE INDEX uq_cart_open_per_user ON cart(user_id) WHERE status = 'OPEN';
```

### `V4_20__create_cart_item.sql`
```sql
CREATE TABLE cart_item (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id             UUID NOT NULL REFERENCES cart(id) ON DELETE CASCADE,
    source              VARCHAR(8) NOT NULL DEFAULT 'MANUAL',  -- MANUAL | EXCEL
    excel_row_num       INT,
    -- pickup
    sender_name         VARCHAR(100) NOT NULL,
    sender_phone        VARCHAR(15)  NOT NULL,
    sender_email        VARCHAR(254),
    origin_address      JSONB NOT NULL,                        -- embedded Address
    origin_city         VARCHAR(100) NOT NULL,
    origin_pincode      VARCHAR(10)  NOT NULL,
    -- drop
    receiver_name       VARCHAR(100) NOT NULL,
    receiver_phone      VARCHAR(15)  NOT NULL,
    receiver_email      VARCHAR(254),
    dest_address        JSONB NOT NULL,
    dest_city           VARCHAR(100) NOT NULL,
    dest_pincode        VARCHAR(10)  NOT NULL,
    -- parcel
    weight_grams        INT NOT NULL,
    length_cm           SMALLINT NOT NULL,
    width_cm            SMALLINT NOT NULL,
    height_cm           SMALLINT NOT NULL,
    declared_value_paise BIGINT,
    pickup_type         VARCHAR(16) NOT NULL,
    drop_type           VARCHAR(16) NOT NULL,
    -- cached compute (so the cart shows price without re-pricing)
    origin_tile_id      UUID,
    dest_tile_id        UUID,
    delivery_type       VARCHAR(16),
    quoted_total_paise  BIGINT,
    validation_status   VARCHAR(8) NOT NULL DEFAULT 'VALID',   -- VALID | STALE
    booked_shipment_ref VARCHAR(64),                           -- set at checkout
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cart_item_cart ON cart_item(cart_id);
```

> No `payment_mode` column on `cart_item` — payment is decided once per cart at checkout
> (B2C prepaid vs B2B credit), not per line, now that COD is gone.

---

## 3. Backend APIs

### Address book — `AddressBookController` (`/api/v1/addresses`)
- `GET /` — list current user's saved addresses
- `POST /` — create (label required; `save_as`, contact, instructions optional)
- `PUT /{id}` · `DELETE /{id}` — user-scoped via `Authz.requireUserId`

### Cart — `CartController` (`/api/v1/cart`)
- `GET /` — active cart: items + per-item price + total + counts
- `POST /items` — add one shipment draft; runs serviceability + pricing, caches tile/quote
- `PUT /items/{id}` · `DELETE /items/{id}`
- `POST /checkout` — books every item via `BookingService.book(...)`; B2C mints one Razorpay
  order for the total, B2B debits the credit account. Returns per-item result
  (`shipment_ref` or failure). **Idempotent** (reuses the `Idempotency-Key` filter).

### Excel — `BulkUploadController` (`/api/v1/bulk`)
- `GET /template` — streams the fixed-header `.xlsx` with one example row
- `POST /upload` (multipart) — parse → validate each row → add VALID rows to cart →
  return `{ added, failed, failures: [{row, errors:[{field, reason}]}] }`

---

## 4. Excel design

- **Library:** add `com.dhatim:fastexcel` + `fastexcel-reader` (lightweight streaming
  `.xlsx`; smaller than full Apache POI).
- **Fixed headers** (derived from `BookingRequest`; `?` = optional):
  ```
  sender_name, sender_phone, sender_email?,
  origin_line1, origin_line2?, origin_city, origin_pincode, origin_state, origin_lat?, origin_lon?,
  receiver_name, receiver_phone, receiver_email?,
  dest_line1, dest_line2?, dest_city, dest_pincode, dest_state, dest_lat?, dest_lon?,
  weight_grams, length_cm, width_cm, height_cm, declared_value_inr,
  pickup_type, drop_type
  ```
  (No `payment_mode` column — COD is gone and the cart decides payment at checkout.)
  Rows without lat/lon fall back to the adapter's **pincode-prefix serviceability**.
- **Per-row validation (reuses existing rules, no duplication):**
  1. Header check (exact set/order) — else reject the whole file.
  2. Map row → `BookingRequest`; run **Jakarta Bean Validation** (the `@NotBlank` /
     `@Pattern` / `@Max` annotations already on `BookingRequest`).
  3. **Serviceability** (both legs) + **pricing** via existing ports → cache tile + quote.
  4. Valid → `cart_item(source=EXCEL, excel_row_num=n)`. Invalid → collected.
- **Response report** drives the dismissable error modal:
  ```json
  { "added": 42, "failed": 3,
    "failures": [
      {"row": 5, "errors": [{"field": "dest_pincode", "reason": "not serviceable in any covered city"}]},
      {"row": 9, "errors": [{"field": "sender_phone", "reason": "must be +91XXXXXXXXXX"}]}
    ] }
  ```
- **Synchronous, capped at ~500 rows** (inline report). Larger files → async job (future).

---

## 5. Demo UI (static `index.html`, Leaflet)

- **Two-step capture:** map pin → reverse-geocode (Nominatim, already wired) auto-fills
  Area/Locality → details form (House/Flat/Floor, Building/Street, HOME/OFFICE/OTHER,
  contact, instructions) → **Save**.
- **Saved-address picker** in the booking form for pickup & drop ("choose saved / add new").
- **Cart view:** staged shipments, per-item price, edit/remove, total, **Checkout**.
- **Excel panel:** download template, upload, render the added/failed report as the
  **dismissable error modal** (closes on ✕).

---

## 6. Phasing (4 reviewable PRs)

- **PR A — Address book — ✅ DONE (backend + tests):** `Address` extra fields, `V4_18`,
  `SavedAddress` entity/repo/DTOs, `AddressBookController` + service, 404 handler,
  idempotency exempt-paths fix, Flyway test `out-of-order` fix. `AddressBookE2eTest` (5 green).
- **PR B — Cart core — ✅ DONE (backend + tests):** `V4_19/20`, `Cart`/`CartItem` +
  repos/DTOs, add/list/edit/remove, checkout (B2C aggregate Razorpay via new
  `BookingService.bookSettled`; B2B per-item credit via `b2bBookingService`), partial-failure
  handling. `CartE2eTest` (5 green).
- **PR C — Excel — ✅ DONE (backend + tests):** `fastexcel` dep, `GET /bulk/template`,
  `POST /bulk/upload` (parse + per-row validation + add-to-cart + failure report, 500-row cap).
  `BulkUploadE2eTest` (3 green).
- **PR D — UI — ✅ DONE:** new "🛒 Cart & Bulk" tab (customer roles) with Saved Addresses
  (map-pin → details form → save/list/delete), Cart (add-from-booking-form, list/remove,
  checkout), and Bulk Excel (download template, upload, **dismissable failure modal**). COD
  removed from the booking form. New `js/cart.js`; booking map picker extended with an
  `onConfirm` hook so the address form reuses it. Added backend `POST /api/v1/cart/payment-order`
  to mint a single Razorpay order for the cart total (completes the B2C checkout contract).

### Implementation notes / deviations
- `saved_address.user_id` / `cart.user_id` are **plain UUIDs (no FK to `users`)** — matches the
  repo convention (`shipments.booked_by_user_id`); orders does not couple to auth's schema.
- The orders **test** Flyway config needed `out-of-order: true` (top-level `V10` sorts as 10 > 4.x,
  else new `V4_NN` migrations are silently skipped). Fixed in `orders/src/test/resources/application.yml`.
- B2C cart shipments are persisted via `bookSettled` (no per-shipment `PaymentTransaction`); the
  aggregate Razorpay refs + total live on the `cart` row. Per-shipment payment attribution is a
  deferred refinement.

---

## 7. Open / deferred

- Async Excel for very large files (>500 rows).
- Door/building photos (deferred from v1).
- Wallet/top-up model for B2B (currently credit-line only, which already exists).
