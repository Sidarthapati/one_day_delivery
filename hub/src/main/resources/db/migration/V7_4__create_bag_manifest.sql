-- M7 §14.3, §7.3 — the system-generated packing list, snapshotting every parcel in the bag at seal.
-- Append-only and immutable (NFR-1, M7-D-008); a reschedule regenerates a NEW row that supersedes
-- the prior one via supersedes_id, never mutates it.
CREATE TABLE bag_manifest (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bag_id        UUID         NOT NULL REFERENCES flight_bag(id),
    flight_no     VARCHAR(20)  NOT NULL,
    parcel_count  INT          NOT NULL,
    weight_grams  INT          NOT NULL,
    parcels       JSONB        NOT NULL,           -- [{parcelId, shipmentRef, destPincode, weightGrams}]
    supersedes_id UUID         REFERENCES bag_manifest(id),
    generated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_bag_manifest_bag ON bag_manifest (bag_id);
