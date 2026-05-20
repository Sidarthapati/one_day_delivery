CREATE TABLE payment_transactions (
  id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  shipment_id           UUID        NOT NULL REFERENCES shipments(id),
  razorpay_order_id     VARCHAR(100) NOT NULL UNIQUE,
  razorpay_payment_id   VARCHAR(100),
  razorpay_signature    VARCHAR(500),
  amount_paise          BIGINT      NOT NULL,
  tax_paise             BIGINT      NOT NULL DEFAULT 0,
  total_paise           BIGINT      NOT NULL,
  currency              VARCHAR(3)  NOT NULL DEFAULT 'INR',
  status                VARCHAR(30) NOT NULL,
  refund_id             VARCHAR(100),
  refund_status         VARCHAR(20),
  refund_amount_paise   BIGINT,
  payment_method        VARCHAR(50),
  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_payments_updated_at
  BEFORE UPDATE ON payment_transactions
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
