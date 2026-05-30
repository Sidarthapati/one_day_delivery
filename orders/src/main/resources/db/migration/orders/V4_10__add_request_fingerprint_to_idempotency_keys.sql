-- PR #8: add SHA-256 body fingerprint column to idempotency_keys.
-- Used by IdempotencyFilter to detect key-reuse with a different request body
-- (returns 422 IDEMPOTENCY_KEY_BODY_MISMATCH in that case).
--
-- VARCHAR(64) because SHA-256 always produces exactly 64 lowercase hex characters.
-- NULLable: rows created before this migration have no fingerprint (safe — they
-- will not be replayed; they will expire and be purged by IdempotencyKeyPurgeJob).

ALTER TABLE idempotency_keys
    ADD COLUMN request_fingerprint VARCHAR(64) NULL;
