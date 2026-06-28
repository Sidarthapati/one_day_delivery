-- M7 §14.3 — a numbered physical spot on the hub floor (a shelf/cage). A stand has NO intrinsic
-- "kind": it is just a physical entity. It becomes "the Mumbai-flight stand" or "the South-route
-- stand" only while a bag sits on it, and is free again afterwards (dynamic allocation, M7-D-001).
-- `zone` is a soft physical-area hint (which part of the floor — near the airport dock vs the
-- delivery dock), never a hard constraint. Status-mutable.
CREATE TABLE stand (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id    UUID        NOT NULL,
    hub_id     UUID        NOT NULL,
    stand_no   VARCHAR(16) NOT NULL,
    zone       VARCHAR(32),
    capacity   INT         NOT NULL,
    status     VARCHAR(12) NOT NULL DEFAULT 'OPEN',  -- OPEN | OCCUPIED | CLOSED
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_stand_no ON stand (hub_id, stand_no);
CREATE INDEX idx_stand_hub_status ON stand (hub_id, status);
