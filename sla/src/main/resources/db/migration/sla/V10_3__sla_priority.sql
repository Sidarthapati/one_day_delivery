-- M10 triage priority: the sweeper (SlaEngine + PriorityScorer) writes these on every recompute so
-- the control tower is a cheap indexed sort instead of a client-side scramble over hundreds of rows.
ALTER TABLE sla_shipment
    ADD COLUMN priority_score   DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN band             VARCHAR(12)      NOT NULL DEFAULT 'WATCH',
    ADD COLUMN urgency_minutes  INTEGER,
    ADD COLUMN act_by_at        TIMESTAMPTZ,
    ADD COLUMN entered_state_at TIMESTAMPTZ;

-- The control-tower query is: open rows, city-scoped, ordered by priority. Partial index keeps the
-- ranked read index-only over just the open set.
CREATE INDEX idx_sla_shipment_priority ON sla_shipment (priority_score DESC) WHERE closed_at IS NULL;
