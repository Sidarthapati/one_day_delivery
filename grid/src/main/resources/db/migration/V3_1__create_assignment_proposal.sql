-- Nightly DA-to-hex assignment proposals (append-only). One proposal per city per
-- date per run; reruns append new rows. Reviewed/approved out of band by a station
-- manager. da_hex_assignment (V3_8) references assignment_proposal(id), so this must
-- be created first.
CREATE TABLE assignment_proposal (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id              UUID NOT NULL,
    valid_for_date       DATE NOT NULL,
    status               VARCHAR(20) NOT NULL,
    proposal_type        VARCHAR(30) NOT NULL,
    solver_type          VARCHAR(30) NOT NULL,
    adjacency_source     VARCHAR(30) NOT NULL,
    -- Null for BFS_FALLBACK proposals (no optimality bound available)
    optimality_gap_pct   DOUBLE PRECISION,
    total_das            INT NOT NULL,
    coverage_pct         DOUBLE PRECISION,
    -- JSONB array of hex UUID strings where K_available < K_needed
    understaffed_hex_ids JSONB,
    proposed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by          UUID,
    reviewed_at          TIMESTAMPTZ,
    notes                TEXT
);

CREATE INDEX idx_assignment_proposal_city_date ON assignment_proposal (city_id, valid_for_date);
CREATE INDEX idx_assignment_proposal_city_status ON assignment_proposal (city_id, status);

-- One row per DA per proposal. Append-only.
CREATE TABLE assignment_proposal_region (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id            UUID NOT NULL REFERENCES assignment_proposal(id),
    da_id                  UUID NOT NULL,
    n_das_required         INT NOT NULL DEFAULT 1,
    estimated_demand_min   DOUBLE PRECISION NOT NULL,
    estimated_util_pct     DOUBLE PRECISION NOT NULL,
    has_bootstrapped_tiles BOOLEAN NOT NULL,
    UNIQUE (proposal_id, da_id)
);
