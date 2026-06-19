-- M5 dispatch: append-only decision log — one row per assignment attempt (assigned / cross-territory
-- / each deferred reason), capturing the cheapest-insertion + cron-feasibility maths. Never updated
-- or deleted.
CREATE TABLE da_assignment_audit (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),  -- BaseEntity @CreationTimestamp
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),  -- BaseEntity @UpdateTimestamp (append-only: == created_at)
    shipment_id               UUID NOT NULL,
    task_type                 VARCHAR(20) NOT NULL,
    city_id                   UUID NOT NULL,
    tile_id                   UUID NOT NULL,
    da_id_selected            UUID,
    decision                  VARCHAR(40) NOT NULL,
    -- ASSIGNED|CROSS_TERRITORY_ASSIGNED|DEFERRED_NO_DA|DEFERRED_CRON|DEFERRED_FROZEN
    insertion_pos             INT,
    cheapest_insert_extra_sec INT,
    cron_slack_sec            INT,
    used_osrm                 BOOLEAN NOT NULL DEFAULT FALSE,
    decided_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Supports the per-shipment audit lookup (DaAssignmentAuditRepository.findByShipmentId).
CREATE INDEX idx_da_assignment_audit_shipment ON da_assignment_audit (shipment_id);
