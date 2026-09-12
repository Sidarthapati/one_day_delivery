-- A1 — persisted asset-registry close, one row per station shift. Snapshots custody at shift end:
-- counts of what's at the station vs. still out, and whether the vans are back (the enforced rule).
CREATE TABLE asset_shift_close (
    id                    UUID PRIMARY KEY,
    city_id               UUID NOT NULL,
    shift                 VARCHAR(20) NOT NULL,
    close_date            DATE NOT NULL,
    closed_by_user_id     UUID,
    closed_at             TIMESTAMPTZ NOT NULL,
    total_assets          INT NOT NULL,
    at_station_count      INT NOT NULL,
    out_with_da_count     INT NOT NULL,
    in_maintenance_count  INT NOT NULL,
    vans_total            INT NOT NULL,
    vans_outstanding      INT NOT NULL,
    discrepancy_count     INT NOT NULL,
    outstanding           JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Read the closes for a city, newest first (the incoming shift's opening state = the latest close).
CREATE INDEX idx_asset_shift_close_city_date ON asset_shift_close (city_id, close_date DESC, closed_at DESC);
