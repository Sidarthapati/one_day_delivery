-- M7 §14.3 — one row per parcel placed in a flight bag, with the weight it contributed (for
-- accumulation and de-accumulation on removal). Append-only: rows are never deleted; a removal
-- flips status to REMOVED and stamps removed_at.
CREATE TABLE flight_bag_item (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bag_id       UUID         NOT NULL REFERENCES flight_bag(id),
    parcel_id    UUID         NOT NULL,
    shipment_ref VARCHAR(30)  NOT NULL,
    weight_grams INT          NOT NULL,
    status       VARCHAR(10)  NOT NULL DEFAULT 'IN_BAG',   -- IN_BAG | REMOVED
    added_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    removed_at   TIMESTAMPTZ
);

CREATE INDEX idx_flight_bag_item_bag ON flight_bag_item (bag_id);
-- A parcel is in at most one bag at a time.
CREATE UNIQUE INDEX uq_flight_bag_item_in_bag ON flight_bag_item (parcel_id) WHERE status = 'IN_BAG';
