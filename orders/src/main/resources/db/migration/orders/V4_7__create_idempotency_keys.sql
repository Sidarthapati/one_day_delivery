CREATE TABLE idempotency_keys (
  key             VARCHAR(100) NOT NULL,
  user_id         UUID         NOT NULL,
  response_status SMALLINT     NOT NULL,
  response_body   JSONB        NOT NULL,
  expires_at      TIMESTAMPTZ  NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (key, user_id)
);

CREATE INDEX idx_idempotency_expires ON idempotency_keys(expires_at);
