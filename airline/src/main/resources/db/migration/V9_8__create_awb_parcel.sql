-- M9 §6 — per-parcel linkage for a booking: one row per parcel that was in the sealed bag, with the
-- share of the AWB's total cost that parcel carries (proportional to its own weight, not a flat even
-- split — a heavier package carries a fairer share, §10).
CREATE TABLE awb_parcel (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    awb_id                UUID         NOT NULL REFERENCES awb(id),
    parcel_id             UUID         NOT NULL,
    shipment_ref          VARCHAR(30)  NOT NULL,
    weight_grams          INTEGER      NOT NULL,
    allocated_cost_paise  BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_awb_parcel_awb ON awb_parcel (awb_id);
CREATE INDEX idx_awb_parcel_parcel ON awb_parcel (parcel_id);
