-- Append-only (C17, M6-D-018). Per-stop, per-DA reconciliation of expected (manifest) vs scanned.
CREATE TABLE handoff_reconciliation (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    manifest_id            UUID        NOT NULL REFERENCES van_manifest(id),
    stop_seq               INT         NOT NULL,
    da_id                  UUID        NOT NULL,
    expected_count         INT         NOT NULL,
    actual_count           INT         NOT NULL,
    discrepancy_type       VARCHAR(20) NOT NULL,        -- MISSING | EXTRA | REJECTED | NONE
    discrepancy_parcel_ids JSONB,
    reason                 TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_handoff_reconciliation_manifest ON handoff_reconciliation (manifest_id);
