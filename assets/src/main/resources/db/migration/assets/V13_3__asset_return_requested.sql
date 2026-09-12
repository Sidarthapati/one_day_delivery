-- A1 shift close: two-step van return. The DA taps "return van to hub custody" (sets this flag, van
-- still ASSIGNED to them); the station manager then approves, flipping the van to the station store.
ALTER TABLE asset
    ADD COLUMN return_requested BOOLEAN NOT NULL DEFAULT FALSE;

-- Vans awaiting a manager's return approval — the shift-close console reads these.
CREATE INDEX idx_asset_return_requested ON asset (city_id) WHERE return_requested = TRUE;
