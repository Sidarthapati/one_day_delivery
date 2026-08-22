-- Append-only parcel-dimension measurements (first-mile "scan dimensions" feature).
--
-- Merchants under-declare L/W/H at booking; this table records additional, source-tagged
-- observations of the SAME parcel WITHOUT ever mutating the customer's declared dimensions on
-- `shipments` (those stay the record of what the customer told us, updatable=false by design).
--
-- One row per observation (never updated): source = who/what measured it — CUSTOMER_DECLARED,
-- DA_PICKUP (this feature), HUB (future). Re-measures append new rows; readers take the latest per
-- source. The declared dimensions are snapshotted onto each row so a dispute has self-contained
-- evidence even if anything upstream changes. Evidence photo object keys (Cloudflare R2) are stored
-- as a JSON array; the bucket is private, so the ops console reads them via short-lived presigned GETs.
CREATE TABLE parcel_measurement (
    id                       UUID PRIMARY KEY,
    shipment_id              UUID NOT NULL REFERENCES shipments(id),
    shipment_ref             VARCHAR(30) NOT NULL,
    -- CUSTOMER_DECLARED | DA_PICKUP | HUB (extensible)
    source                   VARCHAR(20) NOT NULL,
    -- ARUCO | MANUAL | DECLARED — how the numbers were produced
    method                   VARCHAR(20) NOT NULL,
    -- OK | NO_MARKER | LOW_CONFIDENCE | TIMEOUT | ENGINE_UNAVAILABLE | BAD_INPUT | ERROR
    status                   VARCHAR(30) NOT NULL,
    length_cm                DOUBLE PRECISION,
    width_cm                 DOUBLE PRECISION,
    height_cm                DOUBLE PRECISION,
    volumetric_weight_grams  INTEGER,
    confidence               REAL,
    -- declared snapshot at measurement time (immutable evidence)
    declared_length_cm       SMALLINT,
    declared_width_cm        SMALLINT,
    declared_height_cm       SMALLINT,
    -- discrepancy verdict (moderate tolerance): true when measured materially exceeds declared
    over_declared            BOOLEAN NOT NULL DEFAULT FALSE,
    discrepancy_detail       VARCHAR(300),
    -- R2 object keys for the captured evidence photos
    evidence_keys            JSONB NOT NULL DEFAULT '[]',
    -- DA user id who captured it (null for non-DA sources)
    measured_by              UUID,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_parcel_measurement_shipment ON parcel_measurement (shipment_id, created_at DESC);
-- Fast "which parcels were flagged" scan for the ops/dispute console.
CREATE INDEX idx_parcel_measurement_flagged ON parcel_measurement (over_declared, created_at DESC)
    WHERE over_declared = TRUE;
