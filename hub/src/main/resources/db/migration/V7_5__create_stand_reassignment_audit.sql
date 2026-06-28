-- M7 §14.3, §7.2 (H2, M7-D-008) — a stand-full move records old + new stand and the relabel reason.
-- Append-only; the bag's current_stand_id pointer moves, the history here never mutates.
CREATE TABLE stand_reassignment_audit (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bag_id       UUID         NOT NULL REFERENCES flight_bag(id),
    old_stand_id UUID         NOT NULL REFERENCES stand(id),
    new_stand_id UUID         NOT NULL REFERENCES stand(id),
    actor_id     UUID,
    reason       VARCHAR(120),
    new_label    VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_stand_reassign_bag ON stand_reassignment_audit (bag_id);
