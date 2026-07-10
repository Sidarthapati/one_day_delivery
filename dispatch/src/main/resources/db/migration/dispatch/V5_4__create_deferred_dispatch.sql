-- M5 dispatch: parcels that could not be assigned yet (no DA available / cron-infeasible /
-- cron-locked / DA absent / shift ended). The deferred-retry job re-attempts PENDING rows.
CREATE TABLE deferred_dispatch (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),  -- BaseEntity @CreationTimestamp
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),  -- BaseEntity @UpdateTimestamp
    city_id        UUID NOT NULL,
    shipment_id    UUID NOT NULL,
    task_type      VARCHAR(20) NOT NULL,       -- PICKUP | DELIVERY
    tile_id        UUID NOT NULL,
    task_lat       DOUBLE PRECISION NOT NULL,  -- pickup OR delivery location
    task_lon       DOUBLE PRECISION NOT NULL,
    defer_reason   VARCHAR(50) NOT NULL,       -- NO_DA_AVAILABLE|CRON_INFEASIBLE|CRON_LOCKED|DA_ABSENT|SHIFT_ENDED
    deferred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    retry_after    TIMESTAMPTZ,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING|ASSIGNED|ESCALATED|EXPIRED
    assigned_at    TIMESTAMPTZ,
    escalated_at   TIMESTAMPTZ,
    operating_date DATE NOT NULL
);

-- Partial index drives the retry job's hot query: PENDING rows due for retry in a city.
CREATE INDEX idx_deferred_retry ON deferred_dispatch (city_id, status, retry_after)
    WHERE status = 'PENDING';
