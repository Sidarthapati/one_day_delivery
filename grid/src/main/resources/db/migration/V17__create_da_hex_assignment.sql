CREATE TABLE da_hex_assignment (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    proposal_id   UUID NOT NULL REFERENCES assignment_proposal(id),
    da_id         UUID NOT NULL,
    hex_id        UUID NOT NULL REFERENCES h3_hex(id),
    valid_date    DATE NOT NULL,
    n_das_on_hex  INT  NOT NULL DEFAULT 1,
    status        VARCHAR(20) NOT NULL,
    proposed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by   UUID,
    approved_at   TIMESTAMPTZ,
    UNIQUE(da_id, hex_id, valid_date)
);
