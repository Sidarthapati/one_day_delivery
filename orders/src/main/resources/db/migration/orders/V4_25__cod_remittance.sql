-- B2B Cash-on-Delivery remittance.
-- A B2B vendor ships goods to their buyer; we collect the goods' value (cod_amount_paise) from the
-- buyer on delivery, hold it, then REMIT it to the vendor net of a COD fee. This is money that flows
-- buyer → us → vendor, and is entirely distinct from the shipping fee (vendor → us, credit-billed)
-- and declared_value (insurance/valuation only).

-- The amount to collect from the buyer. NULL ⇒ ordinary (non-COD) shipment.
ALTER TABLE shipments ADD COLUMN cod_amount_paise BIGINT;

-- One collection per COD shipment. Lifecycle:
--   AWAITING_COLLECTION (created at booking) → COLLECTED (on delivery) → REMITTED (on payout)
--   CANCELLED if the shipment is cancelled / returned before delivery.
CREATE TABLE cod_collection (
    id             UUID PRIMARY KEY,
    shipment_id    UUID        NOT NULL UNIQUE REFERENCES shipments (id),
    shipment_ref   VARCHAR(30) NOT NULL,
    b2b_account_id UUID        NOT NULL,
    amount_paise   BIGINT      NOT NULL,
    state          VARCHAR(20) NOT NULL DEFAULT 'AWAITING_COLLECTION',
    collected_at   TIMESTAMPTZ,
    remittance_id  UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cod_collection_acct_state ON cod_collection (b2b_account_id, state);
CREATE INDEX idx_cod_collection_remittance ON cod_collection (remittance_id);

-- A payout batch to one vendor. gross = Σ collected; net = gross − fee. PENDING until the bank
-- transfer is confirmed (utr recorded), then PAID.
CREATE TABLE cod_remittance (
    id               UUID        PRIMARY KEY,
    reference        VARCHAR(30) NOT NULL UNIQUE,
    b2b_account_id   UUID        NOT NULL,
    gross_paise      BIGINT      NOT NULL,
    fee_paise        BIGINT      NOT NULL,
    net_paise        BIGINT      NOT NULL,
    collection_count INT         NOT NULL,
    state            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    utr              VARCHAR(50),
    period_start     TIMESTAMPTZ,
    period_end       TIMESTAMPTZ,
    notes            VARCHAR(500),
    created_by       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at          TIMESTAMPTZ
);
CREATE INDEX idx_cod_remittance_acct ON cod_remittance (b2b_account_id, created_at DESC);

-- Human-friendly remittance reference (RMT/FY26-27/000001), mirroring the invoice serial.
CREATE SEQUENCE IF NOT EXISTS cod_remittance_seq START 1;
