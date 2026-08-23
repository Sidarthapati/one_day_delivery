-- Dwell / ageing primitive on shipments — the one Amazon PackageResults field that maps to Godspeed's
-- model. Both nullable and set AFTER booking (by the M8-scan writer, issue #124), so they don't touch
-- the immutable booking facts. Plain VARCHAR for last_scan_type (churns; avoids enum CAST ceremony).
--
-- Dwell (minutes-since-last-scan) is DERIVED at read (now - last_scan_at), never stored — it powers the
-- ageing report and flags parcels stuck before a hub/flight cutoff.
--
-- The rest of Amazon's PackageResults schema was intentionally dropped: cluster ≈ dest_tile_id + van
-- route, sort_zone/aisle don't fit Godspeed's bag+stand sortation, service_type/rdd are already covered
-- by delivery_type + customer_type + sla_commitment_minutes + eta_promised.
ALTER TABLE shipments
    ADD COLUMN last_scan_at    TIMESTAMPTZ,    -- denormalized latest-scan time (powers dwell/ageing)
    ADD COLUMN last_scan_type  VARCHAR(30);
