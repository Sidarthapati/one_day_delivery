-- M7 §14.3, §6 — the dock record: one row per parcel taken into hub custody, with the arrival mode,
-- direction, and whether it reconciled against the expected manifest (discrepancy_type on mismatch).
-- Append-only (custody continuity, C12; reconciliation, C13).
CREATE TABLE inbound_receipt (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parcel_id       UUID         NOT NULL,
    shipment_ref    VARCHAR(30)  NOT NULL,
    city_id         UUID         NOT NULL,
    hub_id          UUID         NOT NULL,
    arrival_mode    VARCHAR(12)  NOT NULL,        -- VAN | SELF_DROP | AIRPORT
    direction       VARCHAR(12)  NOT NULL,        -- OUTBOUND | INBOUND
    reconciled      BOOLEAN      NOT NULL DEFAULT TRUE,
    discrepancy_type VARCHAR(16),                 -- SHORTFALL | SURPLUS | MISSORT (null when reconciled)
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_inbound_receipt_parcel ON inbound_receipt (parcel_id);
CREATE INDEX idx_inbound_receipt_hub ON inbound_receipt (hub_id, received_at);
