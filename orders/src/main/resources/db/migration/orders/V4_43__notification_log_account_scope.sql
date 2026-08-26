-- Scope in-app notifications by account identity, not by mutable recipient strings.
-- The merchant notifications bell matched rows by billing email / support phone, so two accounts
-- sharing a contact (e.g. one company running several B2B accounts on one billing email) could see
-- each other's notification bodies. Persist the owning B2B account id and query by that instead;
-- recipient stays as delivery metadata. Nullable: platform notifications (OTP etc.) have no account.

ALTER TABLE notification_log ADD COLUMN b2b_account_id UUID;

-- Backs the bell query: an account's recent notifications, newest first.
CREATE INDEX idx_notification_log_account ON notification_log (b2b_account_id, created_at DESC)
    WHERE b2b_account_id IS NOT NULL;
