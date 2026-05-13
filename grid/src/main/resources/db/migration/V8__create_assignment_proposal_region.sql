CREATE TABLE assignment_proposal_region (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id            UUID NOT NULL REFERENCES assignment_proposal(id),
    da_id                  UUID NOT NULL,
    n_das_required         INT NOT NULL DEFAULT 1,
    estimated_demand_min   DOUBLE PRECISION NOT NULL,
    estimated_util_pct     DOUBLE PRECISION NOT NULL,
    has_bootstrapped_tiles BOOLEAN NOT NULL DEFAULT false,
    UNIQUE(proposal_id, da_id)
);
