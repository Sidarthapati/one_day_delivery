-- Midday DA-absence reassignment (M5). A station manager marks one or more DAs absent (with a reason);
-- the system splits their hexes among territory-neighbors (M3) and moves the matching tasks. The plan is
-- previewed first, then applied — either by the manager, or automatically once auto_approve_at passes.
CREATE TABLE da_absence_event (
    id               UUID PRIMARY KEY,
    city_id          UUID        NOT NULL,
    operating_date   DATE        NOT NULL,
    -- Comma-joined absent DA ids (v1: small set, no relational child table needed).
    absent_da_ids    TEXT        NOT NULL,
    reason           VARCHAR(500),
    -- PENDING → APPLIED (manager) / AUTO_APPLIED (timeout) / CANCELLED.
    status           VARCHAR(20) NOT NULL,
    created_by       UUID,
    auto_approve_at  TIMESTAMPTZ NOT NULL,
    applied_at       TIMESTAMPTZ,
    -- The grid INTRADAY_OVERRIDE proposal committed on apply (null while PENDING / if nothing moved).
    proposal_id      UUID,
    orphan_count     INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);

-- The auto-apply sweep scans for still-PENDING plans past their deadline.
CREATE INDEX idx_da_absence_event_pending
    ON da_absence_event (auto_approve_at) WHERE status = 'PENDING';

-- CUSTODY_COLLECT tasks: which absent DA the new owner physically collects the parcel from
-- (task_lat/task_lon already carry the collect location). Null for ordinary pickup/delivery tasks.
ALTER TABLE dispatch_queue ADD COLUMN collect_from_da_id UUID;
