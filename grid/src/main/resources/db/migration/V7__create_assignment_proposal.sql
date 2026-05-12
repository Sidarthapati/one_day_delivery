CREATE TABLE assignment_proposal (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id               UUID NOT NULL,
    valid_for_date        DATE NOT NULL,
    status                VARCHAR(20) NOT NULL,
    proposal_type         VARCHAR(30) NOT NULL DEFAULT 'NIGHTLY',
    solver_type           VARCHAR(30) NOT NULL,
    adjacency_source      VARCHAR(30) NOT NULL,
    optimality_gap_pct    DOUBLE PRECISION,
    total_das             INT NOT NULL,
    coverage_pct          DOUBLE PRECISION,
    understaffed_tile_ids JSONB,
    proposed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by           UUID,
    reviewed_at           TIMESTAMPTZ,
    notes                 TEXT
);
