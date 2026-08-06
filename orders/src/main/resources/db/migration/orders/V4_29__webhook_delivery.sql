-- Outbound webhook delivery audit (P4 developer surface).
-- A merchant registers a webhook_url + webhook_secret on b2b_accounts (columns already exist).
-- On each B2B shipment state change we POST a signed payload and record the attempt here so the
-- merchant (and support) can see what fired and whether it landed. Append-with-status-update.
CREATE TABLE webhook_delivery (
    id             UUID PRIMARY KEY,
    b2b_account_id UUID NOT NULL REFERENCES b2b_accounts(id),
    event          VARCHAR(50)  NOT NULL,
    shipment_ref   VARCHAR(30),
    url            VARCHAR(500) NOT NULL,
    -- PENDING → DELIVERED (2xx) | FAILED (non-2xx / network error)
    status         VARCHAR(20)  NOT NULL,
    response_code  INT,
    attempts       INT          NOT NULL DEFAULT 0,
    payload        TEXT,
    error          VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_delivery_account ON webhook_delivery (b2b_account_id, created_at DESC);
