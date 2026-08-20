-- Server-side record of the amount each wallet recharge was ORDERED for.
-- Razorpay's payment signature covers only orderId|paymentId (NOT the amount), so wallet-recharge
-- confirmation must resolve the credited amount from this table rather than trusting the client body
-- (which previously let a user pay ₹100 and self-credit up to ₹5,00,000). Append-only.
CREATE TABLE wallet_recharge_order (
    id                UUID PRIMARY KEY,
    razorpay_order_id VARCHAR(80) NOT NULL UNIQUE,
    b2b_account_id    UUID NOT NULL REFERENCES b2b_accounts(id),
    amount_paise      BIGINT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallet_recharge_order_account ON wallet_recharge_order (b2b_account_id, created_at DESC);
