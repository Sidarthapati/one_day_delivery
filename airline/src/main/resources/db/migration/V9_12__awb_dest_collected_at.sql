-- M12 shuttle — the destination-side custody fact: when a shuttle agent collects a landed AWB from
-- the airport (DEST_SHUTTLE_IN), stamp it here. This is what makes the shuttle inbound queue a STATE
-- question ("landed and not yet brought to the hub") instead of a calendar one — the queue selects
-- landed AWBs where dest_collected_at IS NULL, regardless of flight_date, and an AWB drops off the
-- moment it's collected. Nullable until collected; the mirror of handed_over_at on the origin side.
ALTER TABLE awb
    ADD COLUMN dest_collected_at TIMESTAMPTZ;
