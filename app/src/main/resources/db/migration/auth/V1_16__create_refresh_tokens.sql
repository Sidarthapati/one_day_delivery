-- Refresh-token rotation + revocation store (B2.1).
-- Access JWTs stay short-lived and self-validating; the refresh token is the only stateful,
-- revocable credential. Only the SHA-256 hash of the raw token is stored (mirrors api_keys).
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash      VARCHAR(64) NOT NULL UNIQUE,       -- SHA-256 hex of the raw token
    user_id         UUID NOT NULL REFERENCES users(id),
    family_id       UUID NOT NULL,                     -- rotation lineage; reuse revokes the family
    expires_at      TIMESTAMP NOT NULL,
    revoked_at      TIMESTAMP,                          -- non-null once rotated/logged-out/revoked
    replaced_by_id  UUID,                               -- the token this one rotated into
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id   ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);
-- Supports the purge of expired/long-revoked rows.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
