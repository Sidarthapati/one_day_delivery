-- M5 dispatch: per-DA task queue.
-- Append-only audit trail (XC-D-002) — rows are never deleted; only status + lifecycle timestamps
-- mutate. Cross-module ids (da_id, shipment_id, tile_id, home_tile_id) are bare UUIDs: no FK across
-- module boundaries.
CREATE TABLE dispatch_queue (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),  -- BaseEntity @CreationTimestamp
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),  -- BaseEntity @UpdateTimestamp
    da_id            UUID NOT NULL,
    city_id          UUID NOT NULL,
    shipment_id      UUID NOT NULL,
    task_type        VARCHAR(20) NOT NULL,       -- PICKUP | DELIVERY
    task_lat         DOUBLE PRECISION NOT NULL,  -- pickup OR delivery location (not pickup-only)
    task_lon         DOUBLE PRECISION NOT NULL,
    tile_id          UUID NOT NULL,
    queue_position   INT NOT NULL,
    status           VARCHAR(20) NOT NULL,       -- QUEUED|IN_PROGRESS|COMPLETED|FAILED|DEFERRED|CANCELLED
    payment_mode     VARCHAR(20),                -- PREPAID | COD
    cross_territory  BOOLEAN NOT NULL DEFAULT FALSE,
    home_tile_id     UUID,                       -- non-null when cross_territory = true
    cron_safe        BOOLEAN NOT NULL,           -- cron feasibility verified at assignment time
    assigned_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expected_eta     TIMESTAMPTZ,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    operating_date   DATE NOT NULL
);

-- Partial unique: at most one ACTIVE task per (da, shipment, type, date). FAILED/CANCELLED rows are
-- excluded so a parcel can be re-assigned after a failed attempt without colliding with the dead row.
CREATE UNIQUE INDEX idx_dispatch_queue_active_unique
    ON dispatch_queue (da_id, shipment_id, task_type, operating_date)
    WHERE status NOT IN ('FAILED', 'CANCELLED');

CREATE INDEX idx_dispatch_queue_da_date  ON dispatch_queue (da_id, operating_date, status);
CREATE INDEX idx_dispatch_queue_shipment ON dispatch_queue (shipment_id);
