-- actor_id and target_user_id are plain UUIDs with no FK — audit trail must survive user deletion
CREATE TABLE role_audit_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id       UUID NOT NULL,
    target_user_id UUID NOT NULL,
    action         VARCHAR(50) NOT NULL,
    previous_role  VARCHAR(100),
    new_role       VARCHAR(100),
    city_id        VARCHAR(50),
    reason         TEXT,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_target ON role_audit_logs(target_user_id, created_at DESC);
CREATE INDEX idx_audit_actor  ON role_audit_logs(actor_id, created_at DESC);
