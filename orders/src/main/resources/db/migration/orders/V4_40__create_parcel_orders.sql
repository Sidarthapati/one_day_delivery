-- Order → N Shipments abstraction (Feature 1).
-- A durable parent for every booking. Single bookings get an order of one; a cart checkout /
-- bulk upload gets one order over all its fanned-out shipments. The transient `cart` remains the
-- pre-checkout basket; `parcel_orders` is the record that survives checkout and that each shipment
-- points back to.
CREATE TABLE parcel_orders (
  id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  order_ref           VARCHAR(30)   NOT NULL UNIQUE,
  customer_type       customer_type NOT NULL,
  b2b_account_id      UUID,                          -- non-null only for B2B orders
  booked_by_user_id   UUID,                          -- M1 user who placed the order (powers "my orders")
  purchase_order_ref  VARCHAR(100),                  -- merchant's own PO ref (B2B); finally has a home
  parcel_count        INTEGER       NOT NULL DEFAULT 0,
  total_price_paise   BIGINT        NOT NULL DEFAULT 0,
  city_id             VARCHAR(10)   NOT NULL,        -- origin city (booking grouping, not a routing unit)
  created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_parcel_orders_booked_by  ON parcel_orders (booked_by_user_id, created_at DESC);
CREATE INDEX idx_parcel_orders_b2b_account ON parcel_orders (b2b_account_id);
CREATE INDEX idx_parcel_orders_city        ON parcel_orders (city_id, created_at DESC);

-- Per-(city, date) order-ref counter — mirrors shipment_ref_counters, serialised via SELECT FOR UPDATE.
-- Ref format: 1DD-ORD-{CITY}-{YYYYMMDD}-{NNNNN}, e.g. 1DD-ORD-BLR-20260824-00001.
CREATE TABLE order_ref_counters (
  city_code VARCHAR(10) NOT NULL,
  date_key  DATE        NOT NULL,
  next_val  INTEGER     NOT NULL DEFAULT 1,
  PRIMARY KEY (city_code, date_key)
);

-- Link each shipment to its parent order. Nullable: rows booked before this feature have none, and
-- cross-module convention is a bare UUID, not a DB foreign key.
ALTER TABLE shipments ADD COLUMN order_id UUID;
CREATE INDEX idx_shipments_order_id ON shipments (order_id);
