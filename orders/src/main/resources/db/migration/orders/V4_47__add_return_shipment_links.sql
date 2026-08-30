-- Return framework: link an original shipment to its return child (<ref>_R) and vice-versa.
-- A return is a NEW child shipment under the SAME parcel_order with reversed geography; these two
-- self-referential pointers let tracking/ops walk between the original and its return.
--   return_of_shipment_id : set on the CHILD, points to the original it returns (immutable, born-with)
--   return_shipment_id    : set on the ORIGINAL, points to the child once a return is spawned
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS return_of_shipment_id UUID;
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS return_shipment_id    UUID;

-- One live return child per original — a partial unique index (the child pointer is null for most rows).
CREATE UNIQUE INDEX IF NOT EXISTS ux_shipments_return_of
    ON shipments (return_of_shipment_id)
    WHERE return_of_shipment_id IS NOT NULL;
