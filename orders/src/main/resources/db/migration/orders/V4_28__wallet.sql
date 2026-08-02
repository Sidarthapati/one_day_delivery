-- Prepaid wallet for B2B accounts (P4).
-- A wallet is a prepaid balance a merchant recharges (via Razorpay) and draws down when booking.
-- Credit stays available for approved accounts; the wallet is the "recharge-then-ship" model for
-- everyone else (Delhivery/Shiprocket-style). Balance lives on the account for cheap gating; every
-- movement is an append-only ledger row so the balance is always reconstructable.
ALTER TABLE b2b_accounts
    ADD COLUMN wallet_balance_paise BIGINT NOT NULL DEFAULT 0;

-- Which funding source paid for a B2B shipment's shipping fee, so cancellation reverses the right
-- ledger (CREDIT → decrement outstanding; WALLET → refund the wallet). Null for non-B2B shipments.
ALTER TABLE shipments
    ADD COLUMN funding_source VARCHAR(10);

CREATE TABLE wallet_transaction (
    id                  UUID PRIMARY KEY,
    b2b_account_id      UUID NOT NULL REFERENCES b2b_accounts(id),
    -- RECHARGE (+), DEBIT (-), REFUND (+), REMITTANCE_CREDIT (+), ADJUSTMENT (±)
    type                VARCHAR(30) NOT NULL,
    -- signed: positive credits the wallet, negative debits it
    amount_paise        BIGINT NOT NULL,
    balance_after_paise BIGINT NOT NULL,
    -- shipment ref / razorpay payment id / remittance ref, for reconciliation
    reference           VARCHAR(80),
    description         VARCHAR(300),
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallet_txn_account ON wallet_transaction (b2b_account_id, created_at DESC);
