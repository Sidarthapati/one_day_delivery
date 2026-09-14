-- Per-check background-verification rows for an onboarding candidate. One row per (candidate, check type),
-- created when the candidate submits (status IN_PROGRESS) and advanced to a Green/Amber/Red/Insufficient
-- verdict by the poll job / manual refresh. vendor_ref ties the checks to one vendor case (mock today).
CREATE TABLE da_bgv_check (
    id            UUID PRIMARY KEY,
    candidate_id  UUID NOT NULL REFERENCES da_onboarding_candidate(id),
    check_type    VARCHAR(20) NOT NULL
        CHECK (check_type IN ('PAN','DRIVING_LICENSE','ADDRESS','DATABASE_CRIMINAL','EFIR','POLICE')),
    status        VARCHAR(15) NOT NULL DEFAULT 'NOT_INITIATED'
        CHECK (status IN ('NOT_INITIATED','IN_PROGRESS','GREEN','AMBER','RED','INSUFFICIENT')),
    vendor_ref    VARCHAR(80),
    message       VARCHAR(300),
    initiated_at  TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (candidate_id, check_type)
);

CREATE INDEX idx_da_bgv_check_candidate ON da_bgv_check (candidate_id);
CREATE INDEX idx_da_bgv_check_status ON da_bgv_check (status);
