-- M6-D-015: the van's mini-hub sort plan, one per (van, loop, day). Status drives the lifecycle
-- BUILDING → LOADED → IN_PROGRESS → RETURNED → RECONCILED, so this row is mutable.
CREATE TABLE van_manifest (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_plan_id  UUID        NOT NULL REFERENCES route_plan(id),
    van_id         UUID        NOT NULL,
    loop_index     INT         NOT NULL,
    valid_date     DATE        NOT NULL,
    status         VARCHAR(20) NOT NULL,            -- BUILDING|LOADED|IN_PROGRESS|RETURNED|RECONCILED
    departed_at    TIMESTAMPTZ,
    returned_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (van_id, loop_index, valid_date)
);

CREATE INDEX idx_van_manifest_plan ON van_manifest (route_plan_id);
