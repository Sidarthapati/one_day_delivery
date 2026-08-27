-- M13 asset registry: the master record for a physical asset owned by a city station (vans, handheld
-- scanners, phones, reusable bags, packaging stock). Mutable — status and current custodian move over
-- the asset's life. Current custody ("last known owner") is denormalized here beside the append-only
-- asset_custody_event ledger (V13_2), exactly like bag.current_stand_id + stand_reassignment_audit.
-- Enum-like columns are VARCHAR + app-side @Enumerated(STRING).
CREATE TABLE asset (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_tag             VARCHAR(40)  NOT NULL,                 -- scannable human code, e.g. DEL-VAN-001
    category              VARCHAR(24)  NOT NULL,                 -- VEHICLE | SCANNER | DEVICE | BAG | PACKAGING | OTHER
    asset_type            VARCHAR(40)  NOT NULL,                 -- VAN | HANDHELD_SCANNER | PHONE | DELIVERY_BAG | ...
    tracking_mode         VARCHAR(16)  NOT NULL DEFAULT 'SERIALIZED', -- SERIALIZED | BULK (BULK reserved)
    name                  VARCHAR(120) NOT NULL,
    description           VARCHAR(500),
    make_model            VARCHAR(120),
    serial_number         VARCHAR(120),
    registration_number   VARCHAR(40),                           -- vehicle number plate
    city_id               UUID         NOT NULL,                 -- owning station (UUID city space, like da_status)
    status                VARCHAR(24)  NOT NULL,                 -- IN_STOCK | ASSIGNED | IN_MAINTENANCE | LOST | DAMAGED | RETIRED
    condition             VARCHAR(16)  NOT NULL DEFAULT 'GOOD',  -- GOOD | FAIR | DAMAGED
    current_holder_type   VARCHAR(16)  NOT NULL,                 -- STATION | USER | VENDOR
    current_holder_id     UUID,                                  -- null when held by the station store
    current_holder_name   VARCHAR(160),                          -- snapshot for display / blame
    held_since            TIMESTAMPTZ,
    ack_pending           BOOLEAN      NOT NULL DEFAULT FALSE,   -- drives the DA "confirm receipt" prompt
    photo_keys            JSONB,                                 -- R2 object keys of registration photos (never URLs)
    metadata              JSONB,                                 -- reserved: van capacity, IMEI, insurance/PUC expiry
    quantity              INT,                                   -- reserved: BULK stock count
    acquired_at           TIMESTAMPTZ,                           -- reserved (financials)
    purchase_cost_paise   BIGINT,                                -- reserved (financials)
    vendor                VARCHAR(160),                          -- reserved (financials)
    warranty_expiry       DATE,                                  -- reserved (financials)
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_asset_tag UNIQUE (asset_tag)
);

CREATE INDEX idx_asset_city_status   ON asset (city_id, status);
CREATE INDEX idx_asset_city_category ON asset (city_id, category);
-- "What does this holder have right now?" — the reverse custody lookup.
CREATE INDEX idx_asset_holder        ON asset (current_holder_type, current_holder_id);

-- Shared trigger fn (already created by orders V4_2). CREATE OR REPLACE keeps this self-contained.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER asset_updated_at
    BEFORE UPDATE ON asset
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
