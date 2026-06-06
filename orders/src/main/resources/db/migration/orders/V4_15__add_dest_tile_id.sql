-- M3 resolves both endpoints of a shipment to H3 grid hexes. We already stored the
-- origin tile (for M5 pickup-DA assignment); capture the destination tile too so M5/M6
-- can assign the delivery DA and plan the delivery-side route. Nullable: pre-existing
-- rows and any non-map booking without coordinates leave it null.
ALTER TABLE shipments ADD COLUMN dest_tile_id UUID;
