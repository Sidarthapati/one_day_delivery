-- Append-only (C17). Every manual override/approval action on a plan, with before/after snapshots.
CREATE TABLE route_override_audit (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_plan_id  UUID        NOT NULL REFERENCES route_plan(id),
    actor_id       UUID        NOT NULL,
    action         VARCHAR(40) NOT NULL,            -- APPROVE | OVERRIDE | REPLAN | FALLBACK
    before_json    JSONB,
    after_json     JSONB,
    reason         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_route_override_audit_plan ON route_override_audit (route_plan_id);
