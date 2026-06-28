-- M7 §14.3 — make bag_manifest the SHARED manifest model for both directions (it was flight-only in
-- PR #1). A destination delivery bag seals a LOAD-LIST manifest exactly as a flight bag seals a
-- FLIGHT manifest (append-only, M7-D-008). So: drop the flight_bag FK (bag_id now references either
-- flight_bag or delivery_bag — the direction column says which), relax flight_no to nullable
-- (delivery load-lists have no flight), and record direction + manifest_kind.
ALTER TABLE bag_manifest DROP CONSTRAINT bag_manifest_bag_id_fkey;
ALTER TABLE bag_manifest ALTER COLUMN flight_no DROP NOT NULL;
ALTER TABLE bag_manifest
    ADD COLUMN direction     VARCHAR(10) NOT NULL DEFAULT 'OUTBOUND',  -- OUTBOUND | INBOUND
    ADD COLUMN manifest_kind VARCHAR(16) NOT NULL DEFAULT 'FLIGHT';    -- FLIGHT | LOAD_LIST
