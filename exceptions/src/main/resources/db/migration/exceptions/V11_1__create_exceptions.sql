-- M11 exceptions / problem-solve / RTO. One live case per shipment (opened by a failure event,
-- closed on resolution) + an append-only action log. Enum-like columns are VARCHAR + app-side
-- @Enumerated(STRING) — no PG enum coupling, same convention as M10.

CREATE TABLE exception_case (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id      UUID        NOT NULL,
    shipment_ref     VARCHAR(64),
    origin_city      VARCHAR(8),
    dest_city        VARCHAR(8),
    delivery_type    VARCHAR(16),
    type             VARCHAR(24) NOT NULL,   -- PICKUP_FAILED | DELIVERY_FAILED | CRON_MISSED | FLIGHT_MISSED
    reason_code      VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    status           VARCHAR(16) NOT NULL DEFAULT 'OPEN',       -- OPEN|IN_PROGRESS|RESCHEDULED|RTO|RESOLVED|CANCELLED
    disposition      VARCHAR(16) NOT NULL DEFAULT 'REATTEMPTABLE', -- REATTEMPTABLE|UNDELIVERABLE|RETURNED|RESOLVED
    attempt_no       INTEGER     NOT NULL DEFAULT 1,
    da_attributable  BOOLEAN     NOT NULL DEFAULT false,
    assigned_to      VARCHAR(64),
    assigned_role    VARCHAR(32),
    resolution       VARCHAR(32),            -- the resolve action that closed it (nullable while open)
    notes            TEXT,
    opened_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The problem-solve queue is: open cases, city-scoped, freshest first. Partial index keeps the read
-- index-only over just the live set.
CREATE INDEX idx_exception_case_open ON exception_case (origin_city, opened_at DESC) WHERE resolved_at IS NULL;
-- One live (unresolved) case per shipment — enforced in code, this index makes the lookup cheap.
CREATE INDEX idx_exception_case_live_shipment ON exception_case (shipment_id) WHERE resolved_at IS NULL;

CREATE TABLE exception_action (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id        UUID        NOT NULL REFERENCES exception_case (id),
    action         VARCHAR(32) NOT NULL,
    acted_by       VARCHAR(64),
    acted_by_role  VARCHAR(32),
    notes          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()   -- BaseEntity carries @UpdateTimestamp; append-only in practice
);
CREATE INDEX idx_exception_action_case ON exception_action (case_id, created_at);
