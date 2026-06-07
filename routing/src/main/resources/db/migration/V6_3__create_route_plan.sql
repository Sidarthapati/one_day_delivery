-- Append-only (C17). Overrides/fallbacks create a NEW revision that supersedes; rows never mutate
-- except the approval stamp (approved_by/at) and status flip to SUPERSEDED. Mirrors assignment_proposal.
CREATE TABLE route_plan (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id               UUID        NOT NULL,
    valid_for_date        DATE        NOT NULL,
    status                VARCHAR(20) NOT NULL,            -- PROPOSED|APPROVED|SUPERSEDED|REJECTED
    source                VARCHAR(20) NOT NULL,            -- NIGHTLY|MANUAL_OVERRIDE|FALLBACK
    solver_type           VARCHAR(20) NOT NULL,            -- OR_TOOLS|SAVINGS
    revision              INT         NOT NULL DEFAULT 1,
    supersedes_plan_id    UUID        REFERENCES route_plan(id),
    vans_used             INT,
    recommended_van_count INT,
    provisioning_flag     VARCHAR(20),                     -- OK|UNDER_PROVISIONED
    n_loops               INT,
    realised_cycle_minutes INT,
    notes                 TEXT,
    approved_by           UUID,
    approved_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_route_plan_city_date_status ON route_plan (city_id, valid_for_date, status);
