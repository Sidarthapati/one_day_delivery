-- M4: a per-user shipment cart. Each user has at most one OPEN cart at a time; checkout
-- books every (valid) item, then the cart is marked CHECKED_OUT (or stays OPEN holding the
-- items that failed validation). Payment for a B2C checkout is settled once for the whole
-- cart, so the aggregate Razorpay refs + total are recorded here for audit.
CREATE TABLE cart (
  id                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                     UUID         NOT NULL,         -- plain UUID, no FK (see saved_address)
  status                      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',  -- OPEN | CHECKED_OUT | ABANDONED
  checkout_razorpay_order_id  VARCHAR(100),
  checkout_razorpay_payment_id VARCHAR(100),
  checkout_total_paise        BIGINT,
  created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- At most one OPEN cart per user.
CREATE UNIQUE INDEX uq_cart_open_per_user ON cart (user_id) WHERE status = 'OPEN';

CREATE TRIGGER trg_cart_updated_at
  BEFORE UPDATE ON cart
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
