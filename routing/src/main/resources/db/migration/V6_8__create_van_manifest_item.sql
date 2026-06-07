-- M6-D-014: a specific parcel bound to a van/loop/stop, one direction. Status advances through the
-- custody points (PLANNED → LOADED → ONBOARD → HANDED_OFF | RECONCILED | EXCEPTION) — mutable.
CREATE TABLE van_manifest_item (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    manifest_id        UUID        NOT NULL REFERENCES van_manifest(id),
    parcel_id          UUID        NOT NULL,
    direction          VARCHAR(10) NOT NULL,            -- DELIVER | COLLECT
    stop_seq           INT,
    meeting_vertex_id  UUID,
    counterparty_da_id UUID,
    sla_deadline       TIMESTAMPTZ,
    status             VARCHAR(20) NOT NULL,            -- PLANNED|LOADED|ONBOARD|HANDED_OFF|RECONCILED|EXCEPTION
    loaded_at          TIMESTAMPTZ,
    handed_off_at      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_van_manifest_item_manifest_stop ON van_manifest_item (manifest_id, stop_seq);
CREATE INDEX idx_van_manifest_item_parcel ON van_manifest_item (parcel_id);
