-- Universal tax invoices — one per shipment, for EVERY customer type (C2C/B2C/B2B), not just B2B.
-- Godspeed issues a GST tax invoice for its logistics service (SAC 996812). Generated lazily on
-- first fetch from the shipment's stored pricing. The IRN/QR (govt e-invoicing) is filled later
-- via EInvoicePort when we cross the e-invoicing threshold; null = not e-registered (pilot).
CREATE SEQUENCE IF NOT EXISTS invoice_seq START 1;

CREATE TABLE invoices (
    id                  UUID PRIMARY KEY,
    shipment_id         UUID NOT NULL UNIQUE REFERENCES shipments(id),
    shipment_ref        VARCHAR(30)  NOT NULL,
    invoice_number      VARCHAR(40)  NOT NULL UNIQUE,
    customer_type       VARCHAR(10)  NOT NULL,
    buyer_user_id       UUID,
    buyer_name          VARCHAR(200),
    buyer_gstin         VARCHAR(15),
    sac_code            VARCHAR(10)  NOT NULL DEFAULT '996812',
    taxable_value_paise BIGINT       NOT NULL,
    cgst_paise          BIGINT       NOT NULL DEFAULT 0,
    sgst_paise          BIGINT       NOT NULL DEFAULT 0,
    igst_paise          BIGINT       NOT NULL DEFAULT 0,
    total_paise         BIGINT       NOT NULL,
    irn                 VARCHAR(80),
    irn_qr              TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ISSUED',
    issued_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_invoices_buyer_user ON invoices (buyer_user_id);
