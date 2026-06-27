-- M7 §14.3, §11 — a rolling overload snapshot per (hub, wave): arrival rate, backlog, stand
-- occupancy, and whether the sort is projected to clear by cutoff. Populated in PR #3 (overwritten/rolling).
CREATE TABLE hub_load_snapshot (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id             UUID         NOT NULL,
    hub_id              UUID         NOT NULL,
    wave_key            VARCHAR(40)  NOT NULL,
    inbound_count       INT          NOT NULL DEFAULT 0,
    awaiting_sort       INT          NOT NULL DEFAULT 0,
    stand_occupancy_pct INT          NOT NULL DEFAULT 0,
    projected_clear_at  TIMESTAMPTZ,
    overloaded          BOOLEAN      NOT NULL DEFAULT FALSE,
    snapshot_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_hub_load_snapshot_hub ON hub_load_snapshot (hub_id, snapshot_at);
