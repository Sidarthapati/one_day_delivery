-- M1 auth — device binding (anti-abuse Phase 4). Bind each refresh-token family to the physical
-- device it was minted on (X-Device-Id), so a shared/rented DA login on a second device is detectable
-- and (when single-active-device is enabled) the older device's session is revoked. Nullable: tokens
-- minted by clients that don't send the header stay valid and simply carry no binding.
ALTER TABLE refresh_tokens ADD COLUMN device_id VARCHAR(64);

-- Fast "this user's active sessions on other devices" lookup for the single-active-device sweep.
CREATE INDEX idx_refresh_tokens_user_device ON refresh_tokens (user_id, device_id)
    WHERE revoked_at IS NULL;
